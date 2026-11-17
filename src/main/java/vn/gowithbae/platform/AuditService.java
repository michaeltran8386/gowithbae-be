package vn.gowithbae.platform;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Giao tiếp dùng chung cho B/C; chỉ nhận thay đổi không chứa dữ liệu riêng hoặc bí mật. */
@Service
public class AuditService {
    private static final Set<String> PRIVATE_KEYS = Set.of("otp", "otpcode", "verificationcode", "codehash", "password", "secret",
            "token", "tokenhash", "accesstoken", "refreshtoken", "invitetoken", "recipientsession", "note",
            "notes", "body", "email", "phone", "authorization");
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void log(UUID actor, String action, String objectType, String objectId,
            Map<String, ?> before, Map<String, ?> after, String reason) {
        rejectPrivateFields(before);
        rejectPrivateFields(after);
        if (action == null || action.isBlank() || objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("Audit cần action và objectType");
        }
        jdbc.sql("""
                INSERT INTO audit_log(actor_id, action, object_type, object_id, before_value, after_value, reason)
                VALUES (:actor, :action, :type, :id, CAST(:before AS jsonb), CAST(:after AS jsonb), :reason)
                """).param("actor", actor).param("action", action).param("type", objectType).param("id", objectId)
                .param("before", before == null ? null : json.writeValueAsString(before))
                .param("after", after == null ? null : json.writeValueAsString(after)).param("reason", reason).update();
    }

    private static void rejectPrivateFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT);
                if (PRIVATE_KEYS.contains(key) || key.endsWith("token") || key.endsWith("password") || key.endsWith("secret")) {
                    throw new IllegalArgumentException("Không đưa dữ liệu riêng hoặc bí mật vào audit");
                }
                rejectPrivateFields(entry.getValue());
            }
        } else if (value instanceof Collection<?> list) {
            list.forEach(AuditService::rejectPrivateFields);
        }
    }
}
