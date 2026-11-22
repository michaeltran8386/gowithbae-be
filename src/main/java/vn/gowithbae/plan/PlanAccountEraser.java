package vn.gowithbae.plan;

import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.platform.AccountLifecycleParticipant;

/**
 * Xóa dữ liệu kế hoạch hẹn của tài khoản bị xóa (phần C). Chạy trước phần cùng chọn vì date_plan trỏ tới
 * choice_session / recipient_session.
 */
@Component
@Order(10)
public class PlanAccountEraser implements AccountLifecycleParticipant {

    private final JdbcClient jdbc;

    public PlanAccountEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void revokeAccess(UUID userId) {
        // Khóa/yêu cầu xóa: link mời xem kế hoạch của người này hết hiệu lực ngay.
        jdbc.sql("""
                UPDATE date_plan_invite i SET revoked_at = now()
                FROM date_plan p WHERE i.plan_id = p.id AND p.owner_id = :uid AND i.revoked_at IS NULL
                """).param("uid", userId).update();
    }

    @Override
    public void eraseData(UUID userId) {
        // Lời nhắc / câu trả lời "đã đi" của chính người này trong kế hoạch người khác.
        jdbc.sql("DELETE FROM date_reminder WHERE recipient_user_id = :uid").param("uid", userId).update();
        jdbc.sql("DELETE FROM visit_self_report WHERE respondent_user_id = :uid").param("uid", userId).update();
        // Kế hoạch người này sở hữu (link mời xóa theo bằng ON DELETE CASCADE).
        jdbc.sql("DELETE FROM date_reminder WHERE plan_id IN (SELECT id FROM date_plan WHERE owner_id = :uid)").param("uid", userId).update();
        jdbc.sql("DELETE FROM visit_self_report WHERE plan_id IN (SELECT id FROM date_plan WHERE owner_id = :uid)").param("uid", userId).update();
        jdbc.sql("DELETE FROM date_plan WHERE owner_id = :uid").param("uid", userId).update();
    }
}
