package vn.gowithbae.choice;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.ChoiceSessionsApiDelegate;
import vn.gowithbae.api.model.CastVoteRequest;
import vn.gowithbae.api.model.ChoiceCandidate;
import vn.gowithbae.api.model.ChoiceSession;
import vn.gowithbae.api.model.ChoiceSessionCreated;
import vn.gowithbae.api.model.ChoiceSessionStatus;
import vn.gowithbae.api.model.CreateChoiceSessionRequest;
import vn.gowithbae.api.model.DatePlan;
import vn.gowithbae.api.model.DatePlanStatus;
import vn.gowithbae.api.model.FinalizeChoiceSessionRequest;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.ShareGrant;
import vn.gowithbae.api.model.ShareGrantCreated;
import vn.gowithbae.api.model.VotePreference;
import vn.gowithbae.place.PlacesDelegate;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.PlaceQueryService;
import vn.gowithbae.platform.SecretHash;

@Service
public class ChoiceSessionsDelegate implements ChoiceSessionsApiDelegate {

    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final PlaceQueryService places;
    private final PlacesDelegate placesDelegate;
    private final SecretHash hash;

    public ChoiceSessionsDelegate(JdbcClient jdbc, CurrentUser current, PlaceQueryService places,
                                  PlacesDelegate placesDelegate, SecretHash hash) {
        this.jdbc = jdbc;
        this.current = current;
        this.places = places;
        this.placesDelegate = placesDelegate;
        this.hash = hash;
    }

    @Override
    @Transactional
    public ResponseEntity<ChoiceSessionCreated> createChoiceSession(CreateChoiceSessionRequest req, String idempotencyKey) {
        current.requireActive();
        UUID userId = current.id();

        Set<UUID> placeIds = req.getPlaceIds();
        if (placeIds == null || placeIds.size() < 2 || placeIds.size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hãy chọn từ 2 đến 5 địa điểm khác nhau");
        }

        for (UUID pid : placeIds) {
            places.requirePublished(pid);
        }

        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO choice_session (id, creator_id, status, created_at)
                VALUES (:id, :creatorId, 'WAITING', :now)
                """)
                .param("id", sessionId)
                .param("creatorId", userId)
                .param("now", now)
                .update();

        int order = 0;
        List<ChoiceCandidate> candidateList = new ArrayList<>();
        for (UUID pid : placeIds) {
            UUID candId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO choice_candidate (id, session_id, place_id, sort_order)
                    VALUES (:id, :sessionId, :placeId, :order)
                    """)
                    .param("id", candId)
                    .param("sessionId", sessionId)
                    .param("placeId", pid)
                    .param("order", order++)
                    .update();

            PlaceCard card = placesDelegate.fetchPlaceCard(pid);
            candidateList.add(new ChoiceCandidate(candId, card, true));
        }

        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = hash.digest(rawToken);
        UUID grantId = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusDays(7);

        jdbc.sql("""
                INSERT INTO share_grant (id, session_id, token_hash, expires_at, created_at)
                VALUES (:id, :sessionId, :hash, :expiresAt, :now)
                """)
                .param("id", grantId)
                .param("sessionId", sessionId)
                .param("hash", tokenHash)
                .param("expiresAt", expiresAt)
                .param("now", now)
                .update();

        URI inviteUrl = URI.create("http://localhost:5173/invite/" + rawToken);
        ShareGrantCreated grantCreated = new ShareGrantCreated(
                grantId,
                expiresAt,
                ShareGrantCreated.StatusEnum.VALID,
                rawToken,
                inviteUrl
        );

        ChoiceSessionCreated created = new ChoiceSessionCreated(
                sessionId,
                ChoiceSessionStatus.WAITING,
                ChoiceSessionCreated.ViewerRoleEnum.CREATOR,
                candidateList,
                now,
                grantCreated
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    public ResponseEntity<List<ChoiceSession>> listMyChoiceSessions() {
        current.requireActive();
        UUID userId = current.id();

        List<UUID> sessionIds = jdbc.sql("""
                SELECT id FROM choice_session
                WHERE creator_id = :userId
                ORDER BY created_at DESC
                """)
                .param("userId", userId)
                .query((rs, i) -> rs.getObject("id", UUID.class))
                .list();

        List<ChoiceSession> result = new ArrayList<>();
        for (UUID sid : sessionIds) {
            var s = fetchSession(sid, userId);
            if (s != null) {
                result.add(s);
            }
        }

        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<ChoiceSession> getChoiceSession(UUID sessionId) {
        current.requireActive();
        UUID userId = current.id();

        ChoiceSession s = fetchSession(sessionId, userId);
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn");
        }
        return ResponseEntity.ok(s);
    }

    @Override
    @Transactional
    public ResponseEntity<Void> castVote(UUID sessionId, UUID candidateId, CastVoteRequest req) {
        current.requireActive();
        UUID userId = current.id();

        String status = jdbc.sql("SELECT status FROM choice_session WHERE id = :id")
                .param("id", sessionId)
                .query((rs, i) -> rs.getString("status"))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn"));

        if ("FINALIZED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên này đã được chốt, không thể đổi lựa chọn");
        }

        boolean validCandidate = jdbc.sql("SELECT count(*) FROM choice_candidate WHERE id = :cid AND session_id = :sid")
                .param("cid", candidateId).param("sid", sessionId).query(Long.class).single() > 0;
        if (!validCandidate) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lựa chọn không thuộc phiên này");
        }

        String pref = req.getPreference().getValue().toUpperCase(Locale.ROOT);

        jdbc.sql("""
                INSERT INTO vote (id, candidate_id, voter_user_id, preference, voted_at)
                VALUES (gen_random_uuid(), :cid, :uid, :pref, now())
                ON CONFLICT (candidate_id, voter_user_id) WHERE voter_user_id IS NOT NULL
                DO UPDATE SET preference = EXCLUDED.preference, voted_at = now()
                """)
                .param("cid", candidateId)
                .param("uid", userId)
                .param("pref", pref)
                .update();

        return ResponseEntity.noContent().build();
    }

    @Override
    @Transactional
    public ResponseEntity<DatePlan> finalizeChoiceSession(UUID sessionId, FinalizeChoiceSessionRequest req, String idempotencyKey) {
        current.requireActive();
        UUID userId = current.id();

        Object[] sessionRow = jdbc.sql("SELECT status, creator_id FROM choice_session WHERE id = :id")
                .param("id", sessionId)
                .query((rs, i) -> new Object[]{rs.getString("status"), rs.getObject("creator_id", UUID.class)})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn"));

        if (!userId.equals(sessionRow[1])) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ người tạo phiên mới có quyền chốt");
        }

        UUID candidateId = req.getCandidateId();
        UUID selectedPlaceId;
        if (candidateId != null) {
            selectedPlaceId = jdbc.sql("SELECT place_id FROM choice_candidate WHERE id = :cid AND session_id = :sid")
                    .param("cid", candidateId).param("sid", sessionId)
                    .query((rs, i) -> rs.getObject("place_id", UUID.class))
                    .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ứng viên không thuộc phiên này"));
        } else {
            selectedPlaceId = jdbc.sql("SELECT place_id FROM choice_candidate WHERE session_id = :sid LIMIT 1")
                    .param("sid", sessionId)
                    .query((rs, i) -> rs.getObject("place_id", UUID.class))
                    .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiên không có địa điểm"));
        }

        places.requirePublished(selectedPlaceId);

        OffsetDateTime scheduledAt = req.getScheduledAt() != null ? req.getScheduledAt() : OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        UUID planId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO date_plan (id, owner_id, place_id, scheduled_at, note, status, choice_session_id, created_at, updated_at)
                VALUES (:id, :ownerId, :placeId, :scheduledAt, :note, 'SCHEDULED', :sessionId, :now, :now)
                """)
                .param("id", planId)
                .param("ownerId", userId)
                .param("placeId", selectedPlaceId)
                .param("scheduledAt", scheduledAt)
                .param("note", req.getNote())
                .param("sessionId", sessionId)
                .param("now", now)
                .update();

        jdbc.sql("UPDATE choice_session SET status = 'FINALIZED', finalized_at = now() WHERE id = :id")
                .param("id", sessionId).update();

        PlaceCard card = placesDelegate.fetchPlaceCard(selectedPlaceId);

        DatePlan plan = new DatePlan(
                planId,
                card,
                scheduledAt,
                DatePlanStatus.SCHEDULED,
                DatePlan.ViewerRoleEnum.OWNER,
                false,
                now,
                now
        ).note(req.getNote()).choiceSessionId(sessionId).placeAvailable(true);

        return ResponseEntity.ok(plan);
    }

    @Override
    @Transactional
    public ResponseEntity<ShareGrantCreated> createShareGrant(UUID sessionId) {
        current.requireActive();
        UUID userId = current.id();

        boolean isCreator = jdbc.sql("SELECT count(*) FROM choice_session WHERE id = :id AND creator_id = :userId")
                .param("id", sessionId).param("userId", userId).query(Long.class).single() > 0;
        if (!isCreator) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn");
        }

        jdbc.sql("UPDATE share_grant SET revoked_at = now() WHERE session_id = :id AND revoked_at IS NULL")
                .param("id", sessionId).update();

        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = hash.digest(rawToken);
        UUID grantId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

        jdbc.sql("""
                INSERT INTO share_grant (id, session_id, token_hash, expires_at, created_at)
                VALUES (:id, :sessionId, :hash, :expiresAt, now())
                """)
                .param("id", grantId)
                .param("sessionId", sessionId)
                .param("hash", tokenHash)
                .param("expiresAt", expiresAt)
                .update();

        URI inviteUrl = URI.create("http://localhost:5173/invite/" + rawToken);
        ShareGrantCreated created = new ShareGrantCreated(
                grantId,
                expiresAt,
                ShareGrantCreated.StatusEnum.VALID,
                rawToken,
                inviteUrl
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    @Transactional
    public ResponseEntity<Void> revokeShareGrant(UUID sessionId, UUID grantId) {
        current.requireActive();
        UUID userId = current.id();

        jdbc.sql("UPDATE share_grant SET revoked_at = now() WHERE id = :gid AND session_id = :sid AND revoked_at IS NULL")
                .param("gid", grantId).param("sid", sessionId).update();

        return ResponseEntity.noContent().build();
    }

    @Override
    @Transactional
    public ResponseEntity<Void> removeRecipient(UUID sessionId) {
        current.requireActive();
        UUID userId = current.id();

        jdbc.sql("UPDATE recipient_session SET revoked_at = now() WHERE session_id = :id AND revoked_at IS NULL")
                .param("id", sessionId).update();

        jdbc.sql("UPDATE choice_session SET status = 'WAITING' WHERE id = :id AND status = 'VOTING'")
                .param("id", sessionId).update();

        return ResponseEntity.noContent().build();
    }

    private ChoiceSession fetchSession(UUID sessionId, UUID userId) {
        var rowOpt = jdbc.sql("""
                SELECT s.id, s.status, s.creator_id, s.created_at, s.finalized_at,
                       u.display_name AS creator_name,
                       r.display_name AS recipient_name,
                       dp.id AS date_plan_id
                FROM choice_session s
                JOIN app_user u ON u.id = s.creator_id
                LEFT JOIN recipient_session r ON r.session_id = s.id AND r.revoked_at IS NULL
                LEFT JOIN date_plan dp ON dp.choice_session_id = s.id
                WHERE s.id = :id
                """)
                .param("id", sessionId)
                .query((rs, i) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("creator_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("finalized_at", OffsetDateTime.class),
                        rs.getString("creator_name"),
                        rs.getString("recipient_name"),
                        rs.getObject("date_plan_id", UUID.class)
                })
                .optional();

        if (rowOpt.isEmpty()) {
            return null;
        }

        Object[] row = rowOpt.get();
        UUID creatorId = (UUID) row[2];
        boolean isCreator = userId.equals(creatorId);

        List<ChoiceCandidate> candidates = jdbc.sql("""
                SELECT c.id, c.place_id,
                       v_my.preference AS my_pref,
                       v_partner.preference AS partner_pref
                FROM choice_candidate c
                LEFT JOIN vote v_my ON v_my.candidate_id = c.id AND v_my.voter_user_id = :userId
                LEFT JOIN vote v_partner ON v_partner.candidate_id = c.id AND v_partner.voter_user_id <> :userId
                WHERE c.session_id = :sid
                ORDER BY c.sort_order
                """)
                .param("sid", sessionId)
                .param("userId", userId)
                .query((rs, i) -> {
                    UUID cid = rs.getObject("id", UUID.class);
                    UUID pid = rs.getObject("place_id", UUID.class);
                    PlaceCard card = placesDelegate.fetchPlaceCard(pid);
                    String myPref = rs.getString("my_pref");
                    String partnerPref = rs.getString("partner_pref");

                    ChoiceCandidate cand = new ChoiceCandidate(cid, card, card != null);
                    if (myPref != null) {
                        cand.setMyVote(VotePreference.fromValue(myPref));
                    }
                    if (partnerPref != null) {
                        cand.setPartnerVote(VotePreference.fromValue(partnerPref));
                    }
                    if ("LIKE".equalsIgnoreCase(myPref) && "LIKE".equalsIgnoreCase(partnerPref)) {
                        cand.setMutualLike(true);
                    }
                    return cand;
                }).list();

        ChoiceSession s = new ChoiceSession(
                (UUID) row[0],
                ChoiceSessionStatus.fromValue((String) row[1]),
                isCreator ? ChoiceSession.ViewerRoleEnum.CREATOR : ChoiceSession.ViewerRoleEnum.RECIPIENT,
                candidates,
                (OffsetDateTime) row[3]
        );
        s.setCreatorName((String) row[5]);
        s.setRecipientName((String) row[6]);
        s.setDatePlanId((UUID) row[7]);
        s.setFinalizedAt((OffsetDateTime) row[4]);

        var grantRow = jdbc.sql("""
                SELECT id, expires_at, revoked_at FROM share_grant
                WHERE session_id = :sid AND revoked_at IS NULL AND expires_at > now()
                ORDER BY created_at DESC LIMIT 1
                """)
                .param("sid", sessionId)
                .query((rs, i) -> new ShareGrant(
                        rs.getObject("id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        ShareGrant.StatusEnum.VALID
                ))
                .optional();
        grantRow.ifPresent(s::setActiveShareGrant);

        return s;
    }
}
