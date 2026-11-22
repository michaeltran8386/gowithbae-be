package vn.gowithbae.choice;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.platform.AccountLifecycleParticipant;

/**
 * Triển khai hook từ Người A (MeDelegate / AdminAccountsDelegate):
 * khi tài khoản bị khóa hoặc yêu cầu xóa, thu hồi ngay toàn bộ ShareGrant và RecipientSession trong cùng transaction (RULE-17).
 */
@Component
@org.springframework.core.annotation.Order(30)
public class AccountShareRevocationHook implements AccountLifecycleParticipant {

    private final JdbcClient jdbc;

    public AccountShareRevocationHook(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void revokeAccess(UUID userId) {
        jdbc.sql("""
                UPDATE recipient_session rs
                SET revoked_at = now()
                FROM choice_session cs
                WHERE rs.session_id = cs.id
                  AND cs.creator_id = :uid
                  AND rs.revoked_at IS NULL
                """)
                .param("uid", userId)
                .update();

        jdbc.sql("""
                UPDATE share_grant sg
                SET revoked_at = now()
                FROM choice_session cs
                WHERE sg.session_id = cs.id
                  AND cs.creator_id = :uid
                  AND sg.revoked_at IS NULL
                """)
                .param("uid", userId)
                .update();
    }

    /**
     * Xóa tài khoản: xóa phiên cùng chọn người này tạo (phiếu, người nhận, link, ứng viên) và phiếu của người này
     * trong phiên người khác. Chạy sau phần kế hoạch (kế hoạch trỏ tới phiên/người nhận đã bị xóa trước).
     */
    @Override
    public void eraseData(UUID userId) {
        jdbc.sql("DELETE FROM vote WHERE voter_user_id = :uid").param("uid", userId).update();
        jdbc.sql("""
                DELETE FROM vote WHERE candidate_id IN (
                    SELECT c.id FROM choice_candidate c JOIN choice_session s ON s.id = c.session_id WHERE s.creator_id = :uid)
                """).param("uid", userId).update();
        // Kế hoạch người khác có thể trỏ tới phiên khách của phiên này (hiếm): gỡ liên kết trước khi xóa phiên khách.
        jdbc.sql("""
                DELETE FROM date_reminder WHERE recipient_session_id IN (
                    SELECT rs.id FROM recipient_session rs JOIN choice_session s ON s.id = rs.session_id WHERE s.creator_id = :uid)
                """).param("uid", userId).update();
        jdbc.sql("""
                DELETE FROM visit_self_report WHERE respondent_recipient_id IN (
                    SELECT rs.id FROM recipient_session rs JOIN choice_session s ON s.id = rs.session_id WHERE s.creator_id = :uid)
                """).param("uid", userId).update();
        jdbc.sql("""
                UPDATE date_plan SET recipient_session_id = NULL WHERE recipient_session_id IN (
                    SELECT rs.id FROM recipient_session rs JOIN choice_session s ON s.id = rs.session_id WHERE s.creator_id = :uid)
                """).param("uid", userId).update();
        jdbc.sql("UPDATE date_plan SET choice_session_id = NULL WHERE choice_session_id IN (SELECT id FROM choice_session WHERE creator_id = :uid)")
                .param("uid", userId).update();
        jdbc.sql("DELETE FROM recipient_session WHERE session_id IN (SELECT id FROM choice_session WHERE creator_id = :uid)")
                .param("uid", userId).update();
        jdbc.sql("DELETE FROM share_grant WHERE session_id IN (SELECT id FROM choice_session WHERE creator_id = :uid)")
                .param("uid", userId).update();
        jdbc.sql("DELETE FROM choice_candidate WHERE session_id IN (SELECT id FROM choice_session WHERE creator_id = :uid)")
                .param("uid", userId).update();
        jdbc.sql("DELETE FROM choice_session WHERE creator_id = :uid").param("uid", userId).update();
    }
}
