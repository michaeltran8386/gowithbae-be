package vn.gowithbae.adminaccount;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.model.AdminSession;
import vn.gowithbae.api.model.AdminSessionPage;
import vn.gowithbae.api.model.AdminUserActivity;
import vn.gowithbae.api.model.AdminUserEvent;
import vn.gowithbae.api.model.PageMeta;

/**
 * Đọc lịch sử phiên và hoạt động của một tài khoản cho trang quản trị.
 * Chỉ đếm và đọc metadata; không trả ghi chú kế hoạch, nội dung tin nhắn hỗ trợ hay mô tả báo sai.
 */
@Service
public class AdminUserInsights {

    private final JdbcClient jdbc;

    public AdminUserInsights(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record LoginSummary(OffsetDateTime lastLoginAt, OffsetDateTime lastSeenAt, int activeSessions, boolean mfaEnrolled) {
    }

    public LoginSummary loginSummary(UUID userId) {
        return jdbc.sql("""
                SELECT max(created_at) AS last_login, max(coalesce(last_seen_at, created_at)) AS last_seen,
                       count(*) FILTER (WHERE revoked_at IS NULL AND expires_at > now()) AS active,
                       EXISTS (SELECT 1 FROM staff_mfa m WHERE m.user_id = :id) AS mfa
                FROM access_session WHERE user_id = :id
                """)
                .param("id", userId)
                .query((rs, i) -> new LoginSummary(rs.getObject("last_login", OffsetDateTime.class),
                        rs.getObject("last_seen", OffsetDateTime.class), rs.getInt("active"), rs.getBoolean("mfa")))
                .single();
    }

    public AdminSessionPage sessions(UUID userId, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM access_session WHERE user_id = :id").param("id", userId)
                .query(Long.class).single();
        var items = jdbc.sql("""
                SELECT id, created_at, expires_at, revoked_at, last_seen_at, ip_address, user_agent,
                       (revoked_at IS NULL AND expires_at > now()) AS active
                FROM access_session WHERE user_id = :id
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """)
                .param("id", userId).param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> new AdminSession(rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getBoolean("active"))
                        .revokedAt(rs.getObject("revoked_at", OffsetDateTime.class))
                        .lastSeenAt(rs.getObject("last_seen_at", OffsetDateTime.class))
                        .ipAddress(rs.getString("ip_address"))
                        .userAgent(rs.getString("user_agent")))
                .list();
        return new AdminSessionPage(items, new PageMeta(page, size, total, (int) ((total + size - 1) / size)));
    }

    public AdminUserActivity activity(UUID userId) {
        AdminUserActivity a = jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM saved_list WHERE owner_id = :id) AS saved_lists,
                  (SELECT count(*) FROM saved_list_item i JOIN saved_list l ON l.id = i.list_id WHERE l.owner_id = :id) AS saved_places,
                  (SELECT count(*) FROM choice_session WHERE creator_id = :id) AS sessions_created,
                  (SELECT count(*) FROM choice_session WHERE creator_id = :id AND status = 'FINALIZED') AS sessions_finalized,
                  (SELECT count(*) FROM date_plan WHERE owner_id = :id AND status = 'SCHEDULED') AS plans_scheduled,
                  (SELECT count(*) FROM date_plan WHERE owner_id = :id AND status = 'CANCELLED') AS plans_cancelled,
                  (SELECT count(*) FROM date_plan WHERE owner_id = :id AND status = 'PAST') AS plans_past,
                  (SELECT count(*) FROM visit_self_report WHERE respondent_user_id = :id AND answer = 'YES') AS visits,
                  (SELECT count(*) FROM support_ticket WHERE owner_id = :id) AS tickets,
                  (SELECT count(*) FROM place_report WHERE sender_user_id = :id) AS reports
                """)
                .param("id", userId)
                .query((rs, i) -> new AdminUserActivity()
                        .savedLists(rs.getLong("saved_lists"))
                        .savedPlaces(rs.getLong("saved_places"))
                        .choiceSessionsCreated(rs.getLong("sessions_created"))
                        .choiceSessionsFinalized(rs.getLong("sessions_finalized"))
                        .plansScheduled(rs.getLong("plans_scheduled"))
                        .plansCancelled(rs.getLong("plans_cancelled"))
                        .plansPast(rs.getLong("plans_past"))
                        .visitsConfirmed(rs.getLong("visits"))
                        .supportTickets(rs.getLong("tickets"))
                        .placeReports(rs.getLong("reports")))
                .single();
        var events = jdbc.sql("""
                SELECT name, occurred_at, object_id, platform FROM analytics_event
                WHERE user_id = :id ORDER BY occurred_at DESC LIMIT 50
                """)
                .param("id", userId)
                .query((rs, i) -> new AdminUserEvent(rs.getString("name"), rs.getObject("occurred_at", OffsetDateTime.class))
                        .objectId(rs.getString("object_id"))
                        .platform(rs.getString("platform")))
                .list();
        return a.recentEvents(events);
    }
}
