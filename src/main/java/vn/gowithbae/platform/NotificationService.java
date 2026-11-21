package vn.gowithbae.platform;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Chỉ ghi thông báo trong tài khoản; không tự gửi email/push hoặc chọn thêm sự kiện OQ-14. */
@Service
public class NotificationService {
    public enum Type { DATE_PLAN_UPDATED, DATE_PLAN_CANCELLED, SUPPORT_REPLIED, SUPPORT_STATUS_CHANGED, ADMIN_ANNOUNCEMENT, DATE_PLAN_INVITE_RESPONDED }

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID notify(UUID recipientId, Type type, String objectType, UUID objectId, String summary) {
        if (recipientId == null || type == null || summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Thông báo cần người nhận, loại và nội dung tối thiểu");
        }
        return jdbc.sql("""
                INSERT INTO notification(recipient_id, type, object_type, object_id, summary)
                VALUES (:recipient, :type, :objectType, :objectId, :summary) RETURNING id
                """).param("recipient", recipientId).param("type", type.name()).param("objectType", objectType)
                .param("objectId", objectId).param("summary", summary).query(UUID.class).single();
    }
}
