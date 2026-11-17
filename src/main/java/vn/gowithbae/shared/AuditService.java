package vn.gowithbae.shared;

import java.util.UUID;

import org.springframework.lang.Nullable;

/** Hợp đồng ghi nhật ký thay đổi quan trọng theo TEAM_SPLIT (cung cấp bởi Người A, cài đặt DB dùng chung). */
public interface AuditService {

    void log(
            @Nullable UUID actorId,
            String action,
            String objectType,
            @Nullable String objectId,
            @Nullable Object before,
            @Nullable Object after,
            @Nullable String reason);
}
