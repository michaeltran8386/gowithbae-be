package vn.gowithbae.auth;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import vn.gowithbae.api.model.MfaActivationLink;
import vn.gowithbae.platform.AuditService;

/**
 * Lệnh vận hành tạo quản trị viên đầu tiên (hoặc khôi phục khi mất điện thoại):
 * {@code java -jar app.jar --server.port=0 --app.admin.bootstrap-email=owner@example.com}.
 * Tạo tài khoản nếu chưa có, gán SUPER_ADMIN, thu hồi phiên cũ, in link kích hoạt TOTP dùng 1 lần rồi thoát.
 * Không in secret; không phải endpoint.
 */
@Component
@ConditionalOnProperty("app.admin.bootstrap-email")
public class AdminBootstrap implements ApplicationRunner {

    private final String email;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MfaActivationService activation;
    private final AuditService audit;
    private final ConfigurableApplicationContext context;

    public AdminBootstrap(@Value("${app.admin.bootstrap-email}") String email, JdbcClient jdbc, TransactionTemplate tx,
            MfaActivationService activation, AuditService audit, ConfigurableApplicationContext context) {
        this.email = AuthService.normalizeEmail(email);
        this.jdbc = jdbc;
        this.tx = tx;
        this.activation = activation;
        this.audit = audit;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null) {
            throw new IllegalArgumentException("app.admin.bootstrap-email không hợp lệ");
        }
        MfaActivationLink link = tx.execute(status -> {
            UUID id = jdbc.sql("SELECT id FROM app_user WHERE lower(email)=:email").param("email", email)
                    .query(UUID.class).optional()
                    .orElseGet(() -> jdbc.sql("INSERT INTO app_user(email, display_name) VALUES (:email, 'Quản trị viên') RETURNING id")
                            .param("email", email).query(UUID.class).single());
            jdbc.sql("UPDATE app_user SET status='ACTIVE', locked_reason=NULL, updated_at=now() WHERE id=:id").param("id", id).update();
            jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'SUPER_ADMIN') ON CONFLICT DO NOTHING")
                    .param("id", id).update();
            jdbc.sql("UPDATE access_session SET revoked_at=now() WHERE user_id=:id AND revoked_at IS NULL").param("id", id).update();
            audit.log(null, "ADMIN_BOOTSTRAPPED", "app_user", id.toString(), null, Map.of("roles", "SUPER_ADMIN"),
                    "Trusted CLI bootstrap");
            return activation.issue(id, null);
        });
        System.out.println();
        System.out.println("Đã cấp quyền SUPER_ADMIN cho " + email + ".");
        System.out.println("Mở link sau trong 24 giờ để quét mã QR bằng Google Authenticator:");
        System.out.println(link.getActivationUrl());
        System.out.println();
        System.exit(SpringApplication.exit(context));
    }
}
