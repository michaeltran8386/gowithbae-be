package vn.gowithbae.common.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Cầu nối ghi {@code audit_log} của phần B ủy quyền về {@link vn.gowithbae.platform.AuditService} (phần A),
 * giữ nguyên {@link Propagation#MANDATORY} và kiểm tra chống ghi nhầm dữ liệu riêng/bí mật.
 */
@Service
public class AuditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final vn.gowithbae.platform.AuditService platformAudit;
    private final ObjectMapper json;

    public AuditService(vn.gowithbae.platform.AuditService platformAudit, ObjectMapper json) {
        this.platformAudit = platformAudit;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(UUID actorId, String action, String objectType, Object objectId, Object before, Object after,
            String reason) {
        platformAudit.log(
                actorId,
                action,
                objectType,
                objectId == null ? null : objectId.toString(),
                toMap(before),
                toMap(after),
                reason);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> toMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, ?>) map;
        }
        return json.convertValue(value, MAP_TYPE);
    }
}
