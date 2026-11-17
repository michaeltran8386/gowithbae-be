package vn.gowithbae.choice;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.CastVoteRequest;
import vn.gowithbae.api.model.ChoiceCandidate;
import vn.gowithbae.api.model.ChoiceSession;
import vn.gowithbae.api.model.ChoiceSessionCreated;
import vn.gowithbae.api.model.ChoiceSessionStatus;
import vn.gowithbae.api.model.CreateChoiceSessionRequest;
import vn.gowithbae.api.model.DatePlan;
import vn.gowithbae.api.model.FinalizeChoiceSessionRequest;
import vn.gowithbae.api.model.JoinShareGrantRequest;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.RecipientSessionToken;
import vn.gowithbae.api.model.ShareGrant;
import vn.gowithbae.api.model.ShareGrantCreated;
import vn.gowithbae.api.model.ShareGrantPreview;
import vn.gowithbae.api.model.VotePreference;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.RecipientPrincipal;
import vn.gowithbae.common.ActorResolver.UserPrincipal;
import vn.gowithbae.common.IdempotencyService;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.common.TokenHashUtil;
import vn.gowithbae.plan.DatePlanService;
import vn.gowithbae.shared.PlaceCatalogService;
import vn.gowithbae.shared.PlaceCatalogService.PlaceSummary;

/** Nghiệp vụ phiên cùng chọn, bỏ phiếu, cấp/thu hồi link mời và chốt thành kế hoạch (FR-10..14, RULE-08..12). */
@Service
public class ChoiceSessionService {

    private static final int SHARE_GRANT_DAYS = 7;

    private final JdbcClient jdbc;
    private final ActorResolver actorResolver;
    private final PlaceCatalogService placeCatalogService;
    private final IdempotencyService idempotencyService;
    private final DatePlanService datePlanService;
    private final Clock clock;

    public ChoiceSessionService(
            JdbcClient jdbc,
            ActorResolver actorResolver,
            PlaceCatalogService placeCatalogService,
            IdempotencyService idempotencyService,
            DatePlanService datePlanService,
            Clock clock) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.placeCatalogService = placeCatalogService;
        this.idempotencyService = idempotencyService;
        this.datePlanService = datePlanService;
        this.clock = clock;
    }

    public List<ChoiceSession> listMyChoiceSessions() {
        UserPrincipal creator = actorResolver.requireActiveUser();
        List<SessionRow> rows = jdbc.sql("""
                SELECT cs.id, cs.creator_id, u.display_name AS creator_name, cs.status, cs.created_at, cs.finalized_at,
                       dp.id AS date_plan_id
                FROM choice_session cs
                JOIN app_user u ON u.id = cs.creator_id
                LEFT JOIN date_plan dp ON dp.choice_session_id = cs.id
                WHERE cs.creator_id = :creatorId
                ORDER BY cs.created_at DESC
                """)
                .param("creatorId", creator.userId())
                .query((rs, rowNum) -> mapSessionRow(rs))
                .list();

        List<ChoiceSession> result = new ArrayList<>(rows.size());
        for (SessionRow row : rows) {
            result.add(assembleChoiceSession(row, ChoiceSession.ViewerRoleEnum.CREATOR, null));
        }
        return result;
    }

    @Transactional
    public ResponseEntity<ChoiceSessionCreated> createChoiceSession(
            CreateChoiceSessionRequest req,
            @Nullable String idempotencyKey) {
        UserPrincipal creator = actorResolver.requireActiveUser();
        return idempotencyService.execute(
                creator.userId().toString(),
                "createChoiceSession",
                idempotencyKey,
                req,
                ChoiceSessionCreated.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(createChoiceSessionInternal(creator, req)));
    }

    private ChoiceSessionCreated createChoiceSessionInternal(UserPrincipal creator, CreateChoiceSessionRequest req) {
        Set<UUID> placeIds = req.getPlaceIds();
        if (placeIds == null || placeIds.size() < 2 || placeIds.size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiên cùng chọn phải gồm 2 đến 5 địa điểm khác nhau");
        }

        Map<UUID, PlaceSummary> places = placeCatalogService.findPlaces(placeIds);
        if (places.size() != placeIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Có địa điểm không tồn tại");
        }
        for (UUID pid : placeIds) {
            if (!places.get(pid).isPublished()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Địa điểm đã chọn hiện không khả dụng");
            }
        }
        if (!placeCatalogService.areAllPlacesSavedByUser(creator.userId(), placeIds)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Chỉ được chọn các địa điểm đã lưu trong danh sách của bạn");
        }

        SessionInsertRow created = jdbc.sql("""
                INSERT INTO choice_session(creator_id, status)
                VALUES (:creatorId, 'WAITING')
                RETURNING id, created_at
                """)
                .param("creatorId", creator.userId())
                .query((rs, rowNum) -> new SessionInsertRow(
                        rs.getObject("id", UUID.class),
                        TimeHelper.getOffsetDateTime(rs, "created_at")))
                .single();

        List<ChoiceCandidate> candidateDtos = new ArrayList<>(placeIds.size());
        int order = 0;
        for (UUID placeId : placeIds) {
            UUID candidateId = jdbc.sql("""
                    INSERT INTO choice_candidate(session_id, place_id, sort_order)
                    VALUES (:sessionId, :placeId, :sortOrder)
                    RETURNING id
                    """)
                    .param("sessionId", created.id())
                    .param("placeId", placeId)
                    .param("sortOrder", order++)
                    .query(UUID.class)
                    .single();
            candidateDtos.add(new ChoiceCandidate(candidateId, places.get(placeId).card(), Boolean.TRUE)
                    .myVote(null)
                    .partnerVote(null)
                    .mutualLike(Boolean.FALSE));
        }

        ShareGrantCreated grantCreated = issueGrantInternal(created.id());
        ShareGrant activeGrant = new ShareGrant(
                grantCreated.getId(),
                grantCreated.getExpiresAt(),
                ShareGrant.StatusEnum.VALID);

        return new ChoiceSessionCreated(
                created.id(),
                ChoiceSessionStatus.WAITING,
                ChoiceSessionCreated.ViewerRoleEnum.CREATOR,
                candidateDtos,
                created.createdAt(),
                grantCreated)
                .creatorName(creator.displayName())
                .recipientName(null)
                .activeShareGrant(activeGrant)
                .datePlanId(null)
                .finalizedAt(null);
    }

    public ChoiceSession getChoiceSession(UUID sessionId) {
        SessionViewerAccess access = resolveViewerAccess(sessionId);
        return assembleChoiceSession(access.session(), access.viewerRole(), access.recipientSessionId());
    }

    @Transactional
    public void castVote(UUID sessionId, UUID candidateId, CastVoteRequest req) {
        SessionViewerAccess access = resolveViewerAccess(sessionId);
        if (access.viewerRole() == ChoiceSession.ViewerRoleEnum.CREATOR) {
            actorResolver.requireActiveUser();
        }

        String currentStatus = jdbc.sql("""
                SELECT status FROM choice_session WHERE id = :sid FOR UPDATE
                """)
                .param("sid", sessionId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn"));
        if ("FINALIZED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên cùng chọn đã chốt, không thể thay đổi phiếu");
        }

        Boolean candidateInSession = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM choice_candidate WHERE id = :cid AND session_id = :sid
                )
                """)
                .param("cid", candidateId)
                .param("sid", sessionId)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(candidateInSession)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lựa chọn không thuộc phiên cùng chọn này");
        }

        if (access.viewerRole() == ChoiceSession.ViewerRoleEnum.CREATOR) {
            jdbc.sql("""
                    INSERT INTO vote(candidate_id, voter_user_id, preference)
                    VALUES (:cid, :uid, :pref)
                    ON CONFLICT (candidate_id, voter_user_id) WHERE voter_user_id IS NOT NULL
                    DO UPDATE SET preference = EXCLUDED.preference, updated_at = now()
                    """)
                    .param("cid", candidateId)
                    .param("uid", access.session().creatorId())
                    .param("pref", req.getPreference().getValue())
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO vote(candidate_id, voter_recipient_id, preference)
                    VALUES (:cid, :rsid, :pref)
                    ON CONFLICT (candidate_id, voter_recipient_id) WHERE voter_recipient_id IS NOT NULL
                    DO UPDATE SET preference = EXCLUDED.preference, updated_at = now()
                    """)
                    .param("cid", candidateId)
                    .param("rsid", access.recipientSessionId())
                    .param("pref", req.getPreference().getValue())
                    .update();
        }

        jdbc.sql("""
                UPDATE choice_session SET status = 'VOTING'
                WHERE id = :sid AND status = 'WAITING'
                """)
                .param("sid", sessionId)
                .update();
    }

    @Transactional
    public ResponseEntity<DatePlan> finalizeChoiceSession(
            UUID sessionId,
            FinalizeChoiceSessionRequest req,
            @Nullable String idempotencyKey) {
        UserPrincipal creator = actorResolver.requireActiveUser();
        return idempotencyService.execute(
                creator.userId().toString(),
                "finalizeChoiceSession:" + sessionId,
                idempotencyKey,
                req,
                DatePlan.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(finalizeInternal(creator, sessionId, req)));
    }

    private DatePlan finalizeInternal(
            UserPrincipal creator,
            UUID sessionId,
            FinalizeChoiceSessionRequest req) {
        String currentStatus = jdbc.sql("""
                SELECT status FROM choice_session
                WHERE id = :sid AND creator_id = :creatorId
                FOR UPDATE
                """)
                .param("sid", sessionId)
                .param("creatorId", creator.userId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn"));

        if ("FINALIZED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên cùng chọn đã được chốt trước đó");
        }

        UUID placeId = jdbc.sql("""
                SELECT place_id FROM choice_candidate
                WHERE id = :cid AND session_id = :sid
                """)
                .param("cid", req.getCandidateId())
                .param("sid", sessionId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Lựa chọn không thuộc phiên cùng chọn này"));

        UUID activeRecipientId = jdbc.sql("""
                SELECT id FROM recipient_session
                WHERE session_id = :sid AND revoked_at IS NULL
                """)
                .param("sid", sessionId)
                .query(UUID.class)
                .optional()
                .orElse(null);

        DatePlan plan = datePlanService.createPlanForFinalizedSession(
                creator.userId(),
                placeId,
                req.getScheduledAt(),
                req.getNote(),
                sessionId,
                activeRecipientId);

        jdbc.sql("""
                UPDATE choice_session
                SET status = 'FINALIZED', finalized_at = now()
                WHERE id = :sid
                """)
                .param("sid", sessionId)
                .update();

        return plan;
    }

    @Transactional
    public ShareGrantCreated createShareGrant(UUID sessionId) {
        UserPrincipal creator = actorResolver.requireActiveUser();
        String status = lockOwnedSessionStatus(sessionId, creator.userId());
        if ("FINALIZED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên đã chốt, không thể cấp thêm link mời");
        }

        // Thu hồi link cũ và phiên người nhận bắt nguồn từ link cũ; link cũ không tự khôi phục (FR-14, OQ-09).
        jdbc.sql("""
                UPDATE recipient_session
                SET revoked_at = now()
                WHERE session_id = :sid AND revoked_at IS NULL
                """)
                .param("sid", sessionId)
                .update();
        jdbc.sql("""
                UPDATE share_grant
                SET revoked_at = now()
                WHERE session_id = :sid AND revoked_at IS NULL
                """)
                .param("sid", sessionId)
                .update();

        return issueGrantInternal(sessionId);
    }

    @Transactional
    public void revokeShareGrant(UUID sessionId, UUID grantId) {
        UserPrincipal creator = actorResolver.requireActiveUser();
        verifyOwnedSession(sessionId, creator.userId());

        int updated = jdbc.sql("""
                UPDATE share_grant
                SET revoked_at = COALESCE(revoked_at, now())
                WHERE id = :grantId AND session_id = :sid
                """)
                .param("grantId", grantId)
                .param("sid", sessionId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy link mời");
        }

        jdbc.sql("""
                UPDATE recipient_session
                SET revoked_at = COALESCE(revoked_at, now())
                WHERE share_grant_id = :grantId AND revoked_at IS NULL
                """)
                .param("grantId", grantId)
                .update();
    }

    @Transactional
    public void removeRecipient(UUID sessionId) {
        UserPrincipal creator = actorResolver.requireActiveUser();
        String status = lockOwnedSessionStatus(sessionId, creator.userId());

        jdbc.sql("""
                UPDATE recipient_session
                SET revoked_at = now()
                WHERE session_id = :sid AND revoked_at IS NULL
                """)
                .param("sid", sessionId)
                .update();

        // Giữ nguyên các bản ghi vote cũ trong DB ("phiếu cũ không bị sửa nghĩa"); chỉ trả trạng thái về WAITING nếu chưa có vote nào của creator.
        if ("VOTING".equals(status)) {
            jdbc.sql("""
                    UPDATE choice_session
                    SET status = 'WAITING'
                    WHERE id = :sid AND status = 'VOTING'
                      AND NOT EXISTS (
                          SELECT 1 FROM vote v
                          JOIN choice_candidate cc ON cc.id = v.candidate_id
                          WHERE cc.session_id = :sid AND v.voter_user_id IS NOT NULL
                      )
                    """)
                    .param("sid", sessionId)
                    .update();
        }
    }

    public ShareGrantPreview previewShareGrant(String inviteToken) {
        GrantLookup grant = findUsableGrantOrThrow410(inviteToken);
        boolean slotAvailable = isSlotAvailable(grant.sessionId(), grant.sessionStatus());
        List<PlaceCard> cards = loadCandidateCards(grant.sessionId());
        return new ShareGrantPreview(grant.creatorName(), cards.size(), cards, slotAvailable)
                .expiresAt(grant.expiresAt());
    }

    @Transactional
    public RecipientSessionToken joinShareGrant(String inviteToken, JoinShareGrantRequest req) {
        String displayName = req.getDisplayName() == null ? "" : req.getDisplayName().trim();
        if (displayName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên hiển thị không được để trống");
        }

        GrantLookup grant = findUsableGrantOrThrow410(inviteToken);
        String sessionStatus = jdbc.sql("""
                SELECT status FROM choice_session WHERE id = :sid FOR UPDATE
                """)
                .param("sid", grant.sessionId())
                .query(String.class)
                .single();

        if ("FINALIZED".equals(sessionStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên cùng chọn đã được chốt");
        }

        Boolean hasActiveRecipient = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM recipient_session WHERE session_id = :sid AND revoked_at IS NULL
                )
                """)
                .param("sid", grant.sessionId())
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(hasActiveRecipient)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Suất người nhận của phiên này đã có người tham gia");
        }

        String rawSessionToken = TokenHashUtil.generateOpaqueToken();
        String sessionHash = TokenHashUtil.sha256Hex(rawSessionToken);
        jdbc.sql("""
                INSERT INTO recipient_session(share_grant_id, session_id, display_name, token_hash)
                VALUES (:grantId, :sid, :displayName, :hash)
                """)
                .param("grantId", grant.grantId())
                .param("sid", grant.sessionId())
                .param("displayName", displayName)
                .param("hash", sessionHash)
                .update();

        jdbc.sql("""
                UPDATE choice_session SET status = 'VOTING'
                WHERE id = :sid AND status = 'WAITING'
                """)
                .param("sid", grant.sessionId())
                .update();

        return new RecipientSessionToken(rawSessionToken, grant.sessionId());
    }

    private ShareGrantCreated issueGrantInternal(UUID sessionId) {
        String rawInviteToken = TokenHashUtil.generateOpaqueToken();
        String tokenHash = TokenHashUtil.sha256Hex(rawInviteToken);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusDays(SHARE_GRANT_DAYS);

        UUID grantId = jdbc.sql("""
                INSERT INTO share_grant(session_id, token_hash, expires_at)
                VALUES (:sid, :hash, :expiresAt)
                RETURNING id
                """)
                .param("sid", sessionId)
                .param("hash", tokenHash)
                .param("expiresAt", expiresAt)
                .query(UUID.class)
                .single();

        return new ShareGrantCreated(
                grantId,
                expiresAt,
                ShareGrantCreated.StatusEnum.VALID,
                rawInviteToken,
                URI.create(actorResolver.buildInviteUrl(rawInviteToken)));
    }

    private GrantLookup findUsableGrantOrThrow410(String inviteToken) {
        if (inviteToken == null || inviteToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Link mời không hợp lệ hoặc đã hết hạn");
        }
        String hash = TokenHashUtil.sha256Hex(inviteToken.trim());
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.sql("""
                SELECT sg.id AS grant_id, sg.session_id, sg.expires_at, sg.revoked_at,
                       cs.status AS session_status, u.display_name AS creator_name, u.status AS creator_status
                FROM share_grant sg
                JOIN choice_session cs ON cs.id = sg.session_id
                JOIN app_user u ON u.id = cs.creator_id
                WHERE sg.token_hash = :hash
                """)
                .param("hash", hash)
                .query((rs, rowNum) -> new GrantLookup(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        TimeHelper.getOffsetDateTime(rs, "expires_at"),
                        TimeHelper.getOffsetDateTime(rs, "revoked_at"),
                        rs.getString("session_status"),
                        rs.getString("creator_name"),
                        rs.getString("creator_status")))
                .optional()
                .filter(g -> g.revokedAt() == null
                        && g.expiresAt().isAfter(now)
                        && !"DELETION_REQUESTED".equals(g.creatorStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Link mời không hợp lệ, đã hết hạn hoặc đã bị thu hồi"));
    }

    private boolean isSlotAvailable(UUID sessionId, String sessionStatus) {
        if ("FINALIZED".equals(sessionStatus)) {
            return false;
        }
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM recipient_session WHERE session_id = :sid AND revoked_at IS NULL)
                """)
                .param("sid", sessionId)
                .query(Boolean.class)
                .single();
        return !Boolean.TRUE.equals(exists);
    }

    private List<PlaceCard> loadCandidateCards(UUID sessionId) {
        List<UUID> placeIds = jdbc.sql("""
                SELECT place_id FROM choice_candidate
                WHERE session_id = :sid
                ORDER BY sort_order ASC
                """)
                .param("sid", sessionId)
                .query(UUID.class)
                .list();
        Map<UUID, PlaceSummary> places = placeCatalogService.findPlaces(placeIds);
        List<PlaceCard> cards = new ArrayList<>(placeIds.size());
        for (UUID pid : placeIds) {
            PlaceSummary summary = places.get(pid);
            if (summary != null) {
                cards.add(summary.card());
            }
        }
        return cards;
    }

    private ChoiceSession assembleChoiceSession(
            SessionRow session,
            ChoiceSession.ViewerRoleEnum viewerRole,
            @Nullable UUID currentRecipientId) {

        Optional<ActiveRecipient> activeRecipient = jdbc.sql("""
                SELECT id, display_name
                FROM recipient_session
                WHERE session_id = :sid AND revoked_at IS NULL
                """)
                .param("sid", session.id())
                .query((rs, rowNum) -> new ActiveRecipient(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name")))
                .optional();

        UUID targetRecipientId = currentRecipientId != null
                ? currentRecipientId
                : activeRecipient.map(ActiveRecipient::id).orElse(null);

        List<CandidateRow> candidateRows = jdbc.sql("""
                SELECT id, place_id
                FROM choice_candidate
                WHERE session_id = :sid
                ORDER BY sort_order ASC
                """)
                .param("sid", session.id())
                .query((rs, rowNum) -> new CandidateRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("place_id", UUID.class)))
                .list();

        Set<UUID> placeIds = candidateRows.stream().map(CandidateRow::placeId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, PlaceSummary> places = placeCatalogService.findPlaces(placeIds);

        Map<UUID, VotePreference> creatorVotes = new LinkedHashMap<>();
        Map<UUID, VotePreference> recipientVotes = new LinkedHashMap<>();
        if (!candidateRows.isEmpty()) {
            jdbc.sql("""
                    SELECT candidate_id, voter_user_id, voter_recipient_id, preference
                    FROM vote
                    WHERE candidate_id IN (:cids)
                    """)
                    .param("cids", candidateRows.stream().map(CandidateRow::id).toList())
                    .query(rs -> {
                        UUID cid = rs.getObject("candidate_id", UUID.class);
                        VotePreference pref = VotePreference.fromValue(rs.getString("preference"));
                        UUID voterUser = rs.getObject("voter_user_id", UUID.class);
                        UUID voterRecipient = rs.getObject("voter_recipient_id", UUID.class);
                        if (session.creatorId().equals(voterUser)) {
                            creatorVotes.put(cid, pref);
                        } else if (targetRecipientId != null && targetRecipientId.equals(voterRecipient)) {
                            recipientVotes.put(cid, pref);
                        }
                    });
        }

        boolean finalized = "FINALIZED".equals(session.status());
        List<ChoiceCandidate> candidates = new ArrayList<>(candidateRows.size());
        for (CandidateRow c : candidateRows) {
            PlaceSummary place = places.get(c.placeId());
            if (place == null) {
                continue;
            }
            VotePreference creatorVote = creatorVotes.get(c.id());
            VotePreference recipientVote = recipientVotes.get(c.id());
            VotePreference myVote = viewerRole == ChoiceSession.ViewerRoleEnum.CREATOR ? creatorVote : recipientVote;
            VotePreference rawPartnerVote = viewerRole == ChoiceSession.ViewerRoleEnum.CREATOR ? recipientVote : creatorVote;
            // OQ-07 (đã chốt với user): ẩn partnerVote trước khi chốt; chỉ hiển thị sau khi FINALIZED.
            VotePreference visiblePartnerVote = finalized ? rawPartnerVote : null;
            boolean mutualLike = creatorVote == VotePreference.LIKE && recipientVote == VotePreference.LIKE;

            candidates.add(new ChoiceCandidate(c.id(), place.card(), place.isPublished())
                    .myVote(myVote)
                    .partnerVote(visiblePartnerVote)
                    .mutualLike(mutualLike));
        }

        ShareGrant activeGrant = viewerRole == ChoiceSession.ViewerRoleEnum.CREATOR
                ? loadLatestShareGrant(session.id()).orElse(null)
                : null;

        return new ChoiceSession(
                session.id(),
                ChoiceSessionStatus.fromValue(session.status()),
                viewerRole,
                candidates,
                session.createdAt())
                .creatorName(session.creatorName())
                .recipientName(activeRecipient.map(ActiveRecipient::displayName).orElse(null))
                .activeShareGrant(activeGrant)
                .datePlanId(session.datePlanId())
                .finalizedAt(session.finalizedAt());
    }

    private Optional<ShareGrant> loadLatestShareGrant(UUID sessionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.sql("""
                SELECT id, expires_at, revoked_at
                FROM share_grant
                WHERE session_id = :sid
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("sid", sessionId)
                .query((rs, rowNum) -> {
                    OffsetDateTime expiresAt = TimeHelper.getOffsetDateTime(rs, "expires_at");
                    OffsetDateTime revokedAt = TimeHelper.getOffsetDateTime(rs, "revoked_at");
                    ShareGrant.StatusEnum st = revokedAt != null
                            ? ShareGrant.StatusEnum.REVOKED
                            : (!expiresAt.isAfter(now) ? ShareGrant.StatusEnum.EXPIRED : ShareGrant.StatusEnum.VALID);
                    return new ShareGrant(rs.getObject("id", UUID.class), expiresAt, st);
                })
                .optional();
    }

    private SessionViewerAccess resolveViewerAccess(UUID sessionId) {
        Optional<UserPrincipal> userOpt = actorResolver.findAuthenticatedUser();
        Optional<RecipientPrincipal> recipientOpt = actorResolver.findRecipientPrincipal();

        Optional<SessionRow> rowOpt = jdbc.sql("""
                SELECT cs.id, cs.creator_id, u.display_name AS creator_name, cs.status, cs.created_at, cs.finalized_at,
                       dp.id AS date_plan_id
                FROM choice_session cs
                JOIN app_user u ON u.id = cs.creator_id
                LEFT JOIN date_plan dp ON dp.choice_session_id = cs.id
                WHERE cs.id = :sid
                """)
                .param("sid", sessionId)
                .query((rs, rowNum) -> mapSessionRow(rs))
                .optional();

        if (rowOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn");
        }
        SessionRow row = rowOpt.get();
        if (userOpt.isPresent() && userOpt.get().userId().equals(row.creatorId())) {
            return new SessionViewerAccess(row, ChoiceSession.ViewerRoleEnum.CREATOR, null);
        }
        if (recipientOpt.isPresent() && recipientOpt.get().choiceSessionId().equals(sessionId)) {
            return new SessionViewerAccess(
                    row,
                    ChoiceSession.ViewerRoleEnum.RECIPIENT,
                    recipientOpt.get().recipientSessionId());
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn");
    }

    private String lockOwnedSessionStatus(UUID sessionId, UUID creatorId) {
        return jdbc.sql("""
                SELECT status FROM choice_session
                WHERE id = :sid AND creator_id = :creatorId
                FOR UPDATE
                """)
                .param("sid", sessionId)
                .param("creatorId", creatorId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn"));
    }

    private void verifyOwnedSession(UUID sessionId, UUID creatorId) {
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM choice_session WHERE id = :sid AND creator_id = :creatorId)
                """)
                .param("sid", sessionId)
                .param("creatorId", creatorId)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên cùng chọn");
        }
    }

    private SessionRow mapSessionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("creator_id", UUID.class),
                rs.getString("creator_name"),
                rs.getString("status"),
                TimeHelper.getOffsetDateTime(rs, "created_at"),
                TimeHelper.getOffsetDateTime(rs, "finalized_at"),
                rs.getObject("date_plan_id", UUID.class));
    }

    private record SessionInsertRow(UUID id, OffsetDateTime createdAt) {}

    private record SessionRow(
            UUID id,
            UUID creatorId,
            String creatorName,
            String status,
            OffsetDateTime createdAt,
            @Nullable OffsetDateTime finalizedAt,
            @Nullable UUID datePlanId) {}

    private record CandidateRow(UUID id, UUID placeId) {}

    private record ActiveRecipient(UUID id, String displayName) {}

    private record SessionViewerAccess(
            SessionRow session,
            ChoiceSession.ViewerRoleEnum viewerRole,
            @Nullable UUID recipientSessionId) {}

    private record GrantLookup(
            UUID grantId,
            UUID sessionId,
            OffsetDateTime expiresAt,
            @Nullable OffsetDateTime revokedAt,
            String sessionStatus,
            String creatorName,
            String creatorStatus) {}
}
