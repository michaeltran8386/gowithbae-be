package vn.gowithbae.plan;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.DatePlanStatus;
import vn.gowithbae.api.model.PlanInviteCreated;
import vn.gowithbae.api.model.PlanInviteRespond;
import vn.gowithbae.api.model.PlanInviteResponse;
import vn.gowithbae.api.model.PlanInviteStatus;
import vn.gowithbae.api.model.PlanInviteView;
import vn.gowithbae.api.model.Warning;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.common.TokenHashUtil;
import vn.gowithbae.platform.NotificationService;
import vn.gowithbae.shared.PlaceCatalogService;
import vn.gowithbae.shared.PlaceCatalogService.PlaceSummary;

/**
 * Link mời người ấy xem kế hoạch tạo tay (không qua cùng chọn) và trả lời có đi hay không.
 *
 * <ul>
 *   <li>Mỗi kế hoạch tối đa một link đang hiệu lực; tạo lại thì thu hồi link cũ (câu trả lời cũ đi theo link cũ).</li>
 *   <li>Token chỉ trả một lần, DB lưu SHA-256 (RULE-12).</li>
 *   <li>Link hết hạn 1 ngày sau giờ hẹn, khi bị thu hồi, hoặc khi chủ không còn ACTIVE → 410.</li>
 *   <li>Người nhận không thấy ghi chú riêng của kế hoạch; trả lời thì chủ nhận thông báo.</li>
 * </ul>
 */
@Service
public class PlanInviteService {

    private static final DateTimeFormatter SUMMARY_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM");

    private final JdbcClient jdbc;
    private final ActorResolver actorResolver;
    private final PlaceCatalogService placeCatalog;
    private final NotificationService notifications;
    private final IcsCalendarFormatter icsFormatter;
    private final Clock clock;
    private final String webBaseUrl;

    public PlanInviteService(JdbcClient jdbc, ActorResolver actorResolver, PlaceCatalogService placeCatalog,
            NotificationService notifications, IcsCalendarFormatter icsFormatter, Clock clock,
            @Value("${app.web.base-url:http://localhost:5173}") String webBaseUrl) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.placeCatalog = placeCatalog;
        this.notifications = notifications;
        this.icsFormatter = icsFormatter;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
    }

    // ───────────── Chủ kế hoạch ─────────────

    @Transactional
    public PlanInviteCreated create(UUID planId) {
        OwnedPlan plan = lockOwnedPlan(planId);
        if (!"SCHEDULED".equals(plan.status()) || !plan.scheduledAt().isAfter(OffsetDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ mời được cho kế hoạch sắp tới, chưa hủy");
        }
        jdbc.sql("UPDATE date_plan_invite SET revoked_at = now() WHERE plan_id = :planId AND revoked_at IS NULL")
                .param("planId", planId).update();
        String token = TokenHashUtil.generateOpaqueToken();
        jdbc.sql("INSERT INTO date_plan_invite(plan_id, token_hash) VALUES (:planId, :hash)")
                .param("planId", planId).param("hash", TokenHashUtil.sha256Hex(token)).update();
        return new PlanInviteCreated(webBaseUrl + "/plan-invite/" + token, expiresAt(plan.scheduledAt()));
    }

    @Transactional(readOnly = true)
    public PlanInviteStatus status(UUID planId) {
        OwnedPlan plan = findOwnedPlan(planId);
        return jdbc.sql("""
                SELECT created_at, response, guest_name, responded_at
                FROM date_plan_invite WHERE plan_id = :planId AND revoked_at IS NULL
                """)
                .param("planId", planId)
                .query((rs, i) -> new PlanInviteStatus(TimeHelper.getOffsetDateTime(rs, "created_at"), expiresAt(plan.scheduledAt()))
                        .response(toResponse(rs.getString("response")))
                        .guestName(rs.getString("guest_name"))
                        .respondedAt(TimeHelper.getOffsetDateTime(rs, "responded_at")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kế hoạch chưa có link mời"));
    }

    @Transactional
    public void revoke(UUID planId) {
        findOwnedPlan(planId);
        int n = jdbc.sql("UPDATE date_plan_invite SET revoked_at = now() WHERE plan_id = :planId AND revoked_at IS NULL")
                .param("planId", planId).update();
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kế hoạch chưa có link mời");
        }
    }

    // ───────────── Người nhận (công khai) ─────────────

    @Transactional(readOnly = true)
    public PlanInviteView view(String token) {
        return toView(findValidInvite(token, false));
    }

    @Transactional
    public PlanInviteView respond(String token, PlanInviteRespond req) {
        InviteRow invite = findValidInvite(token, true);
        if (!"SCHEDULED".equals(invite.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kế hoạch đã hủy hoặc đã qua, không trả lời được nữa");
        }
        String guestName = req.getGuestName() == null || req.getGuestName().isBlank() ? null : req.getGuestName().trim();
        jdbc.sql("""
                UPDATE date_plan_invite SET response = :response, guest_name = COALESCE(:guestName, guest_name), responded_at = now()
                WHERE id = :id
                """)
                .param("response", req.getResponse().getValue())
                .param("guestName", guestName)
                .param("id", invite.inviteId())
                .update();

        String who = guestName != null ? guestName : invite.guestName() != null ? invite.guestName() : "Người ấy";
        String answer = req.getResponse() == PlanInviteResponse.GOING ? "sẽ đi" : "không đi được";
        String when = TimeHelper.inVietnam(invite.scheduledAt()).format(SUMMARY_TIME);
        notifications.notify(invite.ownerId(), NotificationService.Type.DATE_PLAN_INVITE_RESPONDED, "DATE_PLAN", invite.planId(),
                who + " " + answer + ": " + invite.placeName() + " lúc " + when);
        return toView(findValidInvite(token, false));
    }

    @Transactional(readOnly = true)
    public String calendar(String token) {
        InviteRow invite = findValidInvite(token, false);
        PlaceSummary place = place(invite.placeId());
        return icsFormatter.formatEvent(invite.planId(), place.name(), place.address(), invite.scheduledAt(),
                OffsetDateTime.now(clock), null, "CANCELLED".equals(invite.status()));
    }

    // ───────────── Nội bộ ─────────────

    private PlanInviteView toView(InviteRow invite) {
        PlaceSummary place = place(invite.placeId());
        boolean available = place.isPublished();
        List<Warning> warnings = new ArrayList<>();
        if (!available) {
            warnings.add(new Warning("PLACE_UNAVAILABLE", "Địa điểm hiện đang tạm ẩn hoặc ngừng hoạt động."));
        } else if ("UNKNOWN".equals(place.hoursStatus())) {
            warnings.add(new Warning("HOURS_UNKNOWN", "Địa điểm chưa rõ giờ mở cửa, vui lòng kiểm tra lại trước khi đi."));
        }
        return new PlanInviteView(invite.ownerName(), place.card(), invite.scheduledAt(), viewStatus(invite),
                expiresAt(invite.scheduledAt()))
                .placeAvailable(available)
                .response(toResponse(invite.response()))
                .guestName(invite.guestName())
                .warnings(warnings);
    }

    private DatePlanStatus viewStatus(InviteRow invite) {
        if ("CANCELLED".equals(invite.status())) {
            return DatePlanStatus.CANCELLED;
        }
        return invite.scheduledAt().isAfter(OffsetDateTime.now(clock)) ? DatePlanStatus.SCHEDULED : DatePlanStatus.PAST;
    }

    private InviteRow findValidInvite(String token, boolean forUpdate) {
        InviteRow invite = jdbc.sql("""
                SELECT i.id AS invite_id, i.response, i.guest_name,
                       dp.id AS plan_id, dp.owner_id, dp.place_id, dp.scheduled_at, dp.status,
                       u.display_name AS owner_name, u.status AS owner_status, p.name AS place_name
                FROM date_plan_invite i
                JOIN date_plan dp ON dp.id = i.plan_id
                JOIN app_user u ON u.id = dp.owner_id
                JOIN place p ON p.id = dp.place_id
                WHERE i.token_hash = :hash AND i.revoked_at IS NULL
                """ + (forUpdate ? " FOR UPDATE OF i" : ""))
                .param("hash", TokenHashUtil.sha256Hex(token == null ? "" : token.trim()))
                .query((rs, n) -> new InviteRow(
                        rs.getObject("invite_id", UUID.class),
                        rs.getString("response"),
                        rs.getString("guest_name"),
                        rs.getObject("plan_id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("place_id", UUID.class),
                        TimeHelper.getOffsetDateTime(rs, "scheduled_at"),
                        rs.getString("status"),
                        rs.getString("owner_name"),
                        rs.getString("owner_status"),
                        rs.getString("place_name")))
                .optional()
                .orElseThrow(PlanInviteService::gone);
        if (!"ACTIVE".equals(invite.ownerStatus()) || !expiresAt(invite.scheduledAt()).isAfter(OffsetDateTime.now(clock))) {
            throw gone();
        }
        return invite;
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE, "Link mời không hợp lệ, đã hết hạn hoặc đã bị thu hồi");
    }

    private OwnedPlan lockOwnedPlan(UUID planId) {
        return ownedPlan(planId, true);
    }

    private OwnedPlan findOwnedPlan(UUID planId) {
        return ownedPlan(planId, false);
    }

    private OwnedPlan ownedPlan(UUID planId, boolean forUpdate) {
        UUID ownerId = actorResolver.requireActiveUser().userId();
        return jdbc.sql("SELECT status, scheduled_at FROM date_plan WHERE id = :planId AND owner_id = :ownerId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("planId", planId)
                .param("ownerId", ownerId)
                .query((rs, n) -> new OwnedPlan(rs.getString("status"), TimeHelper.getOffsetDateTime(rs, "scheduled_at")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch"));
    }

    private PlaceSummary place(UUID placeId) {
        return placeCatalog.findPlace(placeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại"));
    }

    private static OffsetDateTime expiresAt(OffsetDateTime scheduledAt) {
        return scheduledAt.plusDays(1);
    }

    private static @Nullable PlanInviteResponse toResponse(@Nullable String value) {
        return value == null ? null : PlanInviteResponse.fromValue(value);
    }

    private record OwnedPlan(String status, OffsetDateTime scheduledAt) {}

    private record InviteRow(UUID inviteId, @Nullable String response, @Nullable String guestName, UUID planId, UUID ownerId,
            UUID placeId, OffsetDateTime scheduledAt, String status, String ownerName, String ownerStatus, String placeName) {}
}
