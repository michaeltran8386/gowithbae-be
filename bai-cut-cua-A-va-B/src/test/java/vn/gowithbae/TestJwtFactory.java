package vn.gowithbae;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** B/C: fixture tài khoản đã có; cấp role + phiên thật, không bỏ qua filter chain. */
public class TestJwtFactory {
    private final JdbcClient jdbc;
    private final JwtEncoder encoder;
    public TestJwtFactory(JdbcClient jdbc, JwtEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    public String token(UUID userId, String... roles) {
        for (String role : roles) jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, :role) ON CONFLICT DO NOTHING")
                .param("id", userId).param("role", role).update();
        UUID sid = jdbc.sql("INSERT INTO access_session(user_id, expires_at) VALUES (:id, now() + interval '1 hour') RETURNING id")
                .param("id", userId).query(UUID.class).single();
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("gowithbae").subject(userId.toString()).issuedAt(now)
                .expiresAt(now.plusSeconds(3600)).claim("sid", sid.toString()).claim("roles", Arrays.asList(roles))
                .claim("amr",Arrays.stream(roles).anyMatch(role->!"MEMBER".equals(role))
                        ? java.util.List.of("email_otp","totp"):java.util.List.of("email_otp")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
