package vn.gowithbae.platform;

import java.util.UUID;

/** Module C triển khai để thu hồi grant/phiên khách trong cùng transaction khóa/xóa tài khoản. */
public interface AccountLifecycleParticipant {
    void revokeAccess(UUID userId);
}
