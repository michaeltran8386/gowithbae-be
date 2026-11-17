package vn.gowithbae.shared;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/** Cài đặt ghi bảng audit_log dùng chung (không ghi mật khẩu/token vào before/after). */
@Service
public class JdbcAuditService implements AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void log(
            @Nullable UUID actorId,
            String action,
            String objectType,
            @Nullable String objectId,
            @Nullable Object before,
            @Nullable Object after,
            @Nullable String reason) {

        String beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        String afterJson = after == null ? null : objectMapper.writeValueAsString(after);
        String traceId = MDC.get("traceId");

        jdbc.sql("""
                INSERT INTO audit_log(actor_id, action, object_type, object_id, before_value, after_value, reason, trace_id)
                VALUES (CAST(:actorId AS uuid), :action, :objectType, CAST(:objectId AS text),
                        CAST(:beforeVal AS jsonb), CAST(:afterVal AS jsonb), CAST(:reason AS text), CAST(:traceId AS text))
                """)
                .param("actorId", actorId)
                .param("action", action)
                .param("objectType", objectType)
                .param("objectId", objectId)
                .param("beforeVal", beforeJson)
                .param("afterVal", afterJson)
                .param("reason", reason)
                .param("traceId", traceId)
                .update();
    }
}
