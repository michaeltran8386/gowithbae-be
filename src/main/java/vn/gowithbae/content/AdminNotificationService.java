package vn.gowithbae.content;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.api.model.AdminNotificationRequest;
import vn.gowithbae.api.model.AdminNotificationSent;
import vn.gowithbae.api.model.AdminNotificationSentPage;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.NotificationService;

/**
 * Thông báo do quản trị viên soạn (loại ADMIN_ANNOUNCEMENT), gửi vào hộp thư trong app.
 * Gửi tất cả = ghi một dòng cho mỗi tài khoản đang hoạt động (dự án nhỏ, chưa cần hàng đợi).
 * Lịch sử gửi đọc từ audit_log, không lưu danh sách người nhận.
 */
@Service
public class AdminNotificationService {

    static final String ACTION = "ADMIN_NOTIFICATION_SENT";

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectMapper json;

    public AdminNotificationService(JdbcClient jdbc, NotificationService notifications, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public AdminNotificationSent send(AdminNotificationRequest request, UUID actor) {
        String summary = request.getSummary() == null ? "" : request.getSummary().trim();
        if (summary.isEmpty()) {
            throw Problems.badRequest("Nội dung trống", List.of(Problems.fieldError("summary", "Bắt buộc")));
        }
        boolean toAll = request.getTarget() == AdminNotificationRequest.TargetEnum.ALL;
        long recipients;
        if (toAll) {
            recipients = jdbc.sql("""
                    INSERT INTO notification(recipient_id, type, summary)
                    SELECT id, :type, :summary FROM app_user WHERE status = 'ACTIVE'
                    """).param("type", NotificationService.Type.ADMIN_ANNOUNCEMENT.name()).param("summary", summary).update();
        } else {
            if (request.getUserId() == null) {
                throw Problems.badRequest("Chưa chọn người nhận", List.of(Problems.fieldError("userId", "Bắt buộc khi gửi một người")));
            }
            boolean active = jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id = :id AND status = 'ACTIVE')")
                    .param("id", request.getUserId()).query(Boolean.class).single();
            if (!active) {
                throw Problems.notFound();
            }
            notifications.notify(request.getUserId(), NotificationService.Type.ADMIN_ANNOUNCEMENT, null, null, summary);
            recipients = 1;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("summary", summary);
        after.put("target", request.getTarget().getValue());
        after.put("userId", toAll ? null : request.getUserId().toString());
        after.put("recipients", recipients);
        audit.log(actor, ACTION, "notification", null, null, after, null);
        return new AdminNotificationSent(summary, AdminNotificationSent.TargetEnum.fromValue(request.getTarget().getValue()),
                recipients, OffsetDateTime.now())
                .userId(toAll ? null : request.getUserId())
                .sentBy(actor);
    }

    @Transactional(readOnly = true)
    public AdminNotificationSentPage sent(int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a").param("a", ACTION).query(Long.class).single();
        var items = jdbc.sql("""
                SELECT actor_id, after_value::text AS after, created_at FROM audit_log
                WHERE action = :a ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """)
                .param("a", ACTION).param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> {
                    JsonNode after = json.readTree(rs.getString("after"));
                    String userId = after.path("userId").isNull() || after.path("userId").isMissingNode() ? null
                            : after.path("userId").asString();
                    return new AdminNotificationSent(after.path("summary").asString(),
                            AdminNotificationSent.TargetEnum.fromValue(after.path("target").asString()),
                            after.path("recipients").asLong(), rs.getObject("created_at", OffsetDateTime.class))
                            .userId(userId == null ? null : UUID.fromString(userId))
                            .sentBy(rs.getObject("actor_id", UUID.class));
                })
                .list();
        return new AdminNotificationSentPage(items, new PageMeta(page, size, total, (int) ((total + size - 1) / size)));
    }
}
