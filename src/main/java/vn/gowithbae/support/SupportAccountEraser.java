package vn.gowithbae.support;

import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.platform.AccountLifecycleParticipant;

/**
 * Xóa yêu cầu hỗ trợ của tài khoản bị xóa và gỡ liên kết người gửi khỏi báo sai (phần C).
 * Nội dung báo sai được giữ (nói về địa điểm, cần cho lịch sử dữ liệu); chỉ bỏ người gửi.
 * Tin nhắn người này viết trong ticket của người khác (khi là nhân viên hỗ trợ) được giữ, hiện "Tài khoản đã xóa".
 */
@Component
@Order(20)
public class SupportAccountEraser implements AccountLifecycleParticipant {

    private final JdbcClient jdbc;

    public SupportAccountEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void revokeAccess(UUID userId) {
        // Không có quyền truy cập riêng cần thu hồi: ticket chỉ đọc qua phiên đăng nhập (đã thu hồi ở phần A).
    }

    @Override
    public void eraseData(UUID userId) {
        jdbc.sql("""
                DELETE FROM support_message_file WHERE message_id IN (
                    SELECT m.id FROM support_message m JOIN support_ticket t ON t.id = m.ticket_id WHERE t.owner_id = :uid)
                """).param("uid", userId).update();
        jdbc.sql("DELETE FROM support_message WHERE ticket_id IN (SELECT id FROM support_ticket WHERE owner_id = :uid)")
                .param("uid", userId).update();
        jdbc.sql("DELETE FROM support_ticket WHERE owner_id = :uid").param("uid", userId).update();
        jdbc.sql("UPDATE place_report SET sender_user_id = NULL, updated_at = now() WHERE sender_user_id = :uid")
                .param("uid", userId).update();
    }
}
