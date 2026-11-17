package vn.gowithbae;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Sinh JWT HS256 giống token backend sẽ phát sau OTP (claim sub, roles, sid), ký bằng secret của test.
 * Dùng cho test endpoint cần đăng nhập khi phần Auth chưa xong.
 */
public final class TestJwt {

    /** Phải trùng {@code app.jwt.secret} trong @SpringBootTest properties. */
    public static final String SECRET = "test-secret-test-secret-test-secret-123";

    private TestJwt() {
    }

    public static String bearer(UUID userId, String... roles) {
        return "Bearer " + token(userId, roles);
    }

    public static String token(UUID userId, String... roles) {
        var claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("roles", List.of(roles))
                .claim("sid", UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
