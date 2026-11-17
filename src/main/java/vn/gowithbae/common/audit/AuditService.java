package vn.gowithbae.common.audit;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Ghi audit_log trong cùng transaction với thay đổi nghiệp vụ (FR-31): thay đổi thất bại thì nhật ký cũng không ghi.
 * Bảng chỉ INSERT; không truyền OTP, token, ghi chú riêng vào before/after (NFR-05).
 * Bản tối thiểu do phần B tạo; phần quản trị nhật ký (thành viên A) tiếp quản.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(UUID actorId, String action, String objectType, Object objectId, Object before, Object after,
            String reason) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, object_type, object_id, before_value, after_value, reason)
                VALUES (:actor, :action, :type, :objectId, CAST(:before AS jsonb), CAST(:after AS jsonb), :reason)
                """)
                .param("actor", actorId)
                .param("action", action)
                .param("type", objectType)
                .param("objectId", objectId == null ? null : objectId.toString())
                .param("before", before == null ? null : json.writeValueAsString(before))
                .param("after", after == null ? null : json.writeValueAsString(after))
                .param("reason", reason)
                .update();
    }
}
