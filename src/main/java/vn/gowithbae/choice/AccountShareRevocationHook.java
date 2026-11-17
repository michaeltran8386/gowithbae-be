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
}
