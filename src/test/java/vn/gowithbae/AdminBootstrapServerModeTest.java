package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Chế độ server của lệnh bootstrap (Render không có Shell): khởi động xong vẫn chạy tiếp, tài khoản có SUPER_ADMIN
 * và một link kích hoạt Authenticator đang chờ. Cần Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=",
        "app.admin.bootstrap-email=Boot.Owner@Test.invalid", "app.admin.bootstrap-exit=false"})
class AdminBootstrapServerModeTest {

    @Autowired JdbcClient jdbc;

    @Test
    void grantsSuperAdminAndIssuesActivationWithoutExiting() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM app_user u JOIN user_role r ON r.user_id = u.id AND r.role_code = 'SUPER_ADMIN'
                WHERE u.email = 'boot.owner@test.invalid'
                """).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM mfa_activation m JOIN app_user u ON u.id = m.user_id
                WHERE u.email = 'boot.owner@test.invalid' AND m.used_at IS NULL AND m.expires_at > now()
                """).query(Long.class).single()).isEqualTo(1L);
    }
}
