package vn.gowithbae;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/**
 * Cầu nối phát hành Bearer JWT thật cho các test của phần B thông qua {@link TestJwtFactory} (phần A),
 * bảo đảm DB có {@code user_role}, {@code access_session}, {@code iss = gowithbae} và {@code amr = totp} với nhân viên.
 */
public final class TestJwt {

    /** Phải trùng {@code app.jwt.secret} trong @SpringBootTest properties. */
    public static final String SECRET = "test-secret-test-secret-test-secret-123";

    private TestJwt() {
    }

    public static String bearer(JdbcClient jdbc, JwtEncoder encoder, UUID userId, String... roles) {
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", userId).update();
        return "Bearer " + new TestJwtFactory(jdbc, encoder).token(userId, roles);
    }
}
