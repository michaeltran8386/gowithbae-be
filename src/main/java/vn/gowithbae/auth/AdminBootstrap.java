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
 *
 * <p>Chế độ server ({@code app.admin.bootstrap-exit=false}, vd. biến môi trường {@code APP_ADMIN_BOOTSTRAP_EMAIL} +
 * {@code APP_ADMIN_BOOTSTRAP_EXIT=false} trên Render không có Shell): in link vào log rồi chạy tiếp; tài khoản đã cài
 * Authenticator thì bỏ qua để khởi động lại không đổi link hay thu hồi phiên. Kích hoạt xong nên xóa hai biến này.
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
    private final boolean exitAfter;

    public AdminBootstrap(@Value("${app.admin.bootstrap-email}") String email,
            @Value("${app.admin.bootstrap-exit:true}") boolean exitAfter, JdbcClient jdbc, TransactionTemplate tx,
            MfaActivationService activation, AuditService audit, ConfigurableApplicationContext context) {
        this.email = AuthService.normalizeEmail(email);
        this.exitAfter = exitAfter;
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
        if (!exitAfter && jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user u JOIN staff_mfa m ON m.user_id = u.id WHERE lower(u.email) = :email)")
                .param("email", email).query(Boolean.class).single()) {
            System.out.println("Admin bootstrap: " + email + " đã kích hoạt Authenticator, bỏ qua. Có thể xóa APP_ADMIN_BOOTSTRAP_EMAIL.");
            return;
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
                    exitAfter ? "Trusted CLI bootstrap" : "Trusted server bootstrap (env)");
            return activation.issue(id, null);
        });
        System.out.println();
        System.out.println("Đã cấp quyền SUPER_ADMIN cho " + email + ".");
        System.out.println("Mở link sau trong 24 giờ để quét mã QR bằng Google Authenticator:");
        System.out.println(link.getActivationUrl());
        System.out.println();
        if (exitAfter) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
