package vn.gowithbae.choice;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.ShareGrantsApiDelegate;
import vn.gowithbae.api.model.JoinShareGrantRequest;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.RecipientSessionToken;
import vn.gowithbae.api.model.ShareGrantPreview;
import vn.gowithbae.place.PlacesDelegate;
import vn.gowithbae.platform.SecretHash;

@Service
public class ShareGrantsDelegate implements ShareGrantsApiDelegate {

    private final JdbcClient jdbc;
    private final PlacesDelegate placesDelegate;
    private final SecretHash hash;

    public ShareGrantsDelegate(JdbcClient jdbc, PlacesDelegate placesDelegate, SecretHash hash) {
        this.jdbc = jdbc;
        this.placesDelegate = placesDelegate;
        this.hash = hash;
    }

    @Override
    public ResponseEntity<ShareGrantPreview> previewShareGrant(String inviteToken) {
        String tokenHash = hash.digest(inviteToken);

        var grantOpt = jdbc.sql("""
                SELECT g.id, g.session_id, g.expires_at, g.revoked_at,
                       u.display_name AS creator_name
                FROM share_grant g
                JOIN choice_session s ON s.id = g.session_id
                JOIN app_user u ON u.id = s.creator_id
                WHERE g.token_hash = :hash
                """)
                .param("hash", tokenHash)
                .query((rs, i) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class),
                        rs.getString("creator_name")
                })
                .optional();

        if (grantOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Link hết hạn hoặc đã thu hồi");
        }

        Object[] grant = grantOpt.get();
        OffsetDateTime expiresAt = (OffsetDateTime) grant[2];
        OffsetDateTime revokedAt = (OffsetDateTime) grant[3];
        if (revokedAt != null || (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now()))) {
            throw new ResponseStatusException(HttpStatus.GONE, "Link hết hạn hoặc đã thu hồi");
        }

        UUID sessionId = (UUID) grant[1];
        String creatorName = (String) grant[4];

        boolean hasActiveRecipient = jdbc.sql("SELECT count(*) FROM recipient_session WHERE session_id = :sid AND revoked_at IS NULL")
                .param("sid", sessionId).query(Long.class).single() > 0;

        List<UUID> placeIds = jdbc.sql("SELECT place_id FROM choice_candidate WHERE session_id = :sid ORDER BY sort_order")
                .param("sid", sessionId)
                .query((rs, i) -> rs.getObject("place_id", UUID.class))
                .list();

        List<PlaceCard> candidates = new ArrayList<>();
        for (UUID pid : placeIds) {
            PlaceCard card = placesDelegate.fetchPlaceCard(pid);
            if (card != null) {
                candidates.add(card);
            }
        }

        ShareGrantPreview preview = new ShareGrantPreview(
                creatorName,
                candidates.size(),
                candidates,
                !hasActiveRecipient
        ).expiresAt(expiresAt);

        return ResponseEntity.ok(preview);
    }

    @Override
    @Transactional
    public ResponseEntity<RecipientSessionToken> joinShareGrant(String inviteToken, JoinShareGrantRequest req) {
        String tokenHash = hash.digest(inviteToken);

        var grantOpt = jdbc.sql("""
                SELECT g.id, g.session_id, g.expires_at, g.revoked_at
                FROM share_grant g
                WHERE g.token_hash = :hash
                """)
                .param("hash", tokenHash)
                .query((rs, i) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)
                })
                .optional();

        if (grantOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Link hết hạn hoặc đã thu hồi");
        }

        Object[] grant = grantOpt.get();
        UUID grantId = (UUID) grant[0];
        UUID sessionId = (UUID) grant[1];
        OffsetDateTime expiresAt = (OffsetDateTime) grant[2];
        OffsetDateTime revokedAt = (OffsetDateTime) grant[3];

        if (revokedAt != null || (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now()))) {
            throw new ResponseStatusException(HttpStatus.GONE, "Link hết hạn hoặc đã thu hồi");
        }

        boolean hasActiveRecipient = jdbc.sql("SELECT count(*) FROM recipient_session WHERE session_id = :sid AND revoked_at IS NULL")
                .param("sid", sessionId).query(Long.class).single() > 0;

        if (hasActiveRecipient) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lời mời này đã có người tham gia");
        }

        UUID recipientSessionId = UUID.randomUUID();
        String rawRecipientToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String recipientTokenHash = hash.digest(rawRecipientToken);

        jdbc.sql("""
                INSERT INTO recipient_session (id, share_grant_id, session_id, display_name, token_hash, created_at)
                VALUES (:id, :grantId, :sessionId, :name, :hash, now())
                """)
                .param("id", recipientSessionId)
                .param("grantId", grantId)
                .param("sessionId", sessionId)
                .param("name", req.getDisplayName())
                .param("hash", recipientTokenHash)
                .update();

        jdbc.sql("UPDATE choice_session SET status = 'VOTING' WHERE id = :id AND status = 'WAITING'")
                .param("id", sessionId).update();

        return ResponseEntity.ok(new RecipientSessionToken(rawRecipientToken, sessionId));
    }
}
