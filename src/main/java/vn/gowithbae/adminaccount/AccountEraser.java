package vn.gowithbae.adminaccount;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.gowithbae.platform.AccountLifecycleParticipant;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.upload.FileStorage;

/**
 * Xóa tài khoản bằng ẩn danh hóa (OQ-17, chốt 22/11/2026). Dòng {@code app_user} được giữ lại (nhật ký chỉ-thêm,
 * báo sai, địa điểm… vẫn trỏ tới) nhưng không còn email, tên, ảnh; dữ liệu cá nhân bị xóa:
 * <ul>
 *   <li>Phần A (ở đây): vai trò, phiên đăng nhập (IP, thiết bị), mã OTP, Authenticator, danh sách lưu, thông báo,
 *       khóa chống trùng, tệp người này sở hữu không còn được dùng; sự kiện phân tích được tách khỏi người dùng.</li>
 *   <li>Phần B/C qua {@link AccountLifecycleParticipant#eraseData}: kế hoạch, phiên cùng chọn, ticket, người gửi báo sai.</li>
 * </ul>
 * Người gọi kiểm tra quyền và trạng thái; lớp này chỉ thực thi. Tệp trên bộ lưu trữ bị xóa sau khi commit.
 */
@Component
public class AccountEraser {

    private static final Logger log = LoggerFactory.getLogger(AccountEraser.class);

    private final JdbcClient jdbc;
    private final ObjectProvider<AccountLifecycleParticipant> lifecycle;
    private final AuditService audit;
    private final FileStorage storage;

    public AccountEraser(JdbcClient jdbc, ObjectProvider<AccountLifecycleParticipant> lifecycle, AuditService audit, FileStorage storage) {
        this.jdbc = jdbc;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.storage = storage;
    }

    /** @param actorId quản trị viên thực hiện; null = hệ thống (tự xóa sau thời hạn). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID userId, @Nullable UUID actorId, String reason) {
        String before = jdbc.sql("SELECT status FROM app_user WHERE id = :id FOR UPDATE").param("id", userId).query(String.class).single();

        // Thu hồi truy cập như khi khóa (link chia sẻ, link mời), rồi xóa dữ liệu của từng module theo thứ tự.
        lifecycle.orderedStream().forEach(p -> p.revokeAccess(userId));
        lifecycle.orderedStream().forEach(p -> p.eraseData(userId));

        // Phần A.
        jdbc.sql("UPDATE app_user SET avatar_file_id = NULL WHERE id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM staff_mfa WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM mfa_activation WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM access_session WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("""
                DELETE FROM login_challenge WHERE user_id = :id OR lower(email) = (SELECT lower(email) FROM app_user WHERE id = :id)
                """).param("id", userId).update();
        jdbc.sql("DELETE FROM saved_list WHERE owner_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM notification WHERE recipient_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM idempotency_record WHERE actor_key LIKE '%' || :id || '%'").param("id", userId.toString()).update();
        // Giữ số liệu tổng nhưng không truy ngược được: mỗi người một mã ẩn danh ngẫu nhiên.
        jdbc.sql("UPDATE analytics_event SET user_id = NULL, anonymous_id = :anon WHERE user_id = :id")
                .param("anon", "erased-" + UUID.randomUUID()).param("id", userId).update();

        // Tệp người này sở hữu và không còn nơi nào dùng (avatar, đính kèm ticket đã xóa). Tệp gắn vào báo sai,
        // địa điểm, bộ sưu tập được giữ.
        List<String> keys = jdbc.sql("""
                DELETE FROM stored_file f WHERE f.owner_user_id = :id
                  AND NOT EXISTS (SELECT 1 FROM place_report r WHERE r.file_id = f.id)
                  AND NOT EXISTS (SELECT 1 FROM place_image i WHERE i.file_id = f.id)
                  AND NOT EXISTS (SELECT 1 FROM collection c WHERE c.cover_file_id = f.id)
                  AND NOT EXISTS (SELECT 1 FROM support_message_file m WHERE m.file_id = f.id)
                  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.avatar_file_id = f.id)
                RETURNING f.storage_key
                """).param("id", userId).query(String.class).list();
        jdbc.sql("UPDATE stored_file SET owner_user_id = NULL WHERE owner_user_id = :id").param("id", userId).update();

        jdbc.sql("""
                UPDATE app_user SET email = :email, display_name = 'Tài khoản đã xóa', locked_reason = NULL,
                       status = 'DELETED', deleted_at = now(), deletion_requested_at = NULL, updated_at = now()
                WHERE id = :id
                """).param("email", "deleted+" + userId + "@deleted.invalid").param("id", userId).update();

        audit.log(actorId, "USER_ERASED", "app_user", userId.toString(), Map.of("status", before), Map.of("status", "DELETED"), reason);
        deleteFilesAfterCommit(keys);
    }

    private void deleteFilesAfterCommit(List<String> keys) {
        if (keys.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (IOException e) {
                        // Metadata đã xóa; tệp sót lại không còn đường truy cập. Ghi log để dọn tay nếu cần.
                        log.warn("Không xóa được tệp {} của tài khoản đã xóa: {}", key, e.toString());
                    }
                }
            }
        });
    }
}
