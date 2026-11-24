package vn.gowithbae.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra chữ ký Google ID token qua JWKS công khai của Google, cộng với `iss`, `aud` (= Client ID),
 * `exp`/`nbf`. Không cần client secret vì frontend dùng Google Identity Services lấy ID token trực tiếp.
 */
@Component
public class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifier {
    private static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private volatile NimbusJwtDecoder decoder;

    public NimbusGoogleIdTokenVerifier(@Value("${app.auth.google-client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.strip();
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder d = decoder;
        if (d == null) {
            d = NimbusJwtDecoder.withJwkSetUri(JWKS).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(jwt.getClaimAsString("iss"))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Sai issuer", null));
            OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Sai audience", null));
            d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), issuer, audience));
            decoder = d;
        }
        return d;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId.isEmpty()) throw new InvalidGoogleTokenException("Chưa cấu hình app.auth.google-client-id", null);
        try {
            Jwt jwt = decoder().decode(idToken);
            Object verified = jwt.getClaims().get("email_verified");
            boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified));
            return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), emailVerified, jwt.getClaimAsString("name"));
        } catch (JwtException e) {
            throw new InvalidGoogleTokenException("Google ID token không hợp lệ", e);
        }
    }
}
