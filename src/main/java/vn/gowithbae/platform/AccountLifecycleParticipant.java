package vn.gowithbae.platform;

import java.util.UUID;

/**
 * Module khác (B, C) tham gia vòng đời tài khoản của Người A, trong cùng transaction:
 * <ul>
 *   <li>{@link #revokeAccess}: khi khóa hoặc yêu cầu xóa — thu hồi grant/phiên khách.</li>
 *   <li>{@link #eraseData}: khi xóa tài khoản (ẩn danh hóa) — xóa dữ liệu cá nhân của module mình. Dòng
 *       {@code app_user} được giữ lại nên khóa ngoại tới nó vẫn hợp lệ; chỉ xóa dữ liệu, không xóa người dùng.</li>
 * </ul>
 * Thứ tự gọi theo {@code @Order} (số nhỏ chạy trước), vd. kế hoạch phải xóa trước phiên cùng chọn mà nó trỏ tới.
 */
public interface AccountLifecycleParticipant {
    void revokeAccess(UUID userId);

    default void eraseData(UUID userId) {}
}
