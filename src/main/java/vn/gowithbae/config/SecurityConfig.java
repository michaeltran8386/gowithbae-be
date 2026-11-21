package vn.gowithbae.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.auth.DatabaseJwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Lớp chặn thô theo URL. Quyền chi tiết (sở hữu, cấp quyền chia sẻ, vai trò kép) kiểm tra trong delegate (FR-22, NFR-04).
 * JWT HS256 do chính backend cấp sau khi xác minh OTP; claim "roles" = danh sách Role, "sid" = access_session.id.
 */
@Configuration
public class SecurityConfig {

    private static final String API = "/api/v1";

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper json, DatabaseJwtAuthenticationConverter converter) throws Exception {
        http
            .csrf(c -> c.disable()) // API stateless, token qua header Authorization, không dùng cookie
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                // Công khai: khám phá, tìm, chi tiết, so sánh
                .requestMatchers(HttpMethod.GET, API + "/collections/**", API + "/taxonomies", API + "/areas",
                        API + "/places/**").permitAll()
                // Nội dung trang tĩnh và ảnh địa điểm/bộ sưu tập (delegate chỉ phục vụ ảnh đã gắn)
                .requestMatchers(HttpMethod.GET, API + "/site-content", API + "/files/*").permitAll()
                // Khách ẩn danh được gửi (có chống lạm dụng trong delegate)
                .requestMatchers(HttpMethod.POST, API + "/places/*/reports", API + "/events", API + "/uploads").permitAll()
                .requestMatchers(API + "/auth/otp/**", API + "/share-grants/**").permitAll()
                // Link kích hoạt TOTP: bảo vệ bằng token dùng 1 lần do SUPER_ADMIN/bootstrap cấp
                .requestMatchers(API + "/auth/mfa-activation/*").permitAll()
                // Bearer HOẶC X-Recipient-Session: delegate bắt buộc kiểm tra một trong hai (RULE-12)
                .requestMatchers(HttpMethod.GET, API + "/choice-sessions/*", API + "/date-plans/*",
                        API + "/date-plans/*/calendar.ics", API + "/date-plans/*/reminder").permitAll()
                .requestMatchers(HttpMethod.PUT, API + "/choice-sessions/*/votes/*", API + "/date-plans/*/visit-report",
                        API + "/date-plans/*/reminder").permitAll()
                // Biên tập/duyệt cần xem danh sách nhãn (kể cả ngừng dùng) để gắn cho địa điểm; chỉ thêm/sửa mới cần SUPER_ADMIN.
                .requestMatchers(HttpMethod.GET, API + "/admin/taxonomies").hasAnyRole("EDITOR", "REVIEWER", "SUPER_ADMIN")
                .requestMatchers(API + "/admin/users/**", API + "/admin/audit-logs", API + "/admin/reports/**",
                        API + "/admin/taxonomies/**", API + "/admin/areas/**", API + "/admin/site-content",
                        API + "/admin/notifications/**").hasRole("SUPER_ADMIN")
                .requestMatchers(API + "/admin/places/**", API + "/admin/collections/**", API + "/admin/imports/**")
                        .hasAnyRole("EDITOR", "REVIEWER", "SUPER_ADMIN")
                .requestMatchers(API + "/admin/place-reports/**").hasAnyRole("REVIEWER", "EDITOR", "SUPER_ADMIN")
                .requestMatchers(API + "/admin/support-tickets/**").hasAnyRole("SUPPORT", "SUPER_ADMIN")
                .requestMatchers(API + "/admin/**").hasAnyRole("EDITOR", "REVIEWER", "SUPPORT", "SUPER_ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, failure) -> writeProblem(json, response, 401))
                    .accessDeniedHandler((request, response, failure) -> writeProblem(json, response, 403)))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint((request, response, failure) -> writeProblem(json, response, 401)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret, JdbcClient jdbc) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("app.jwt.secret phải dài tối thiểu 32 byte");
        }
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> session = jwt -> {
            try {
                UUID user = UUID.fromString(jwt.getSubject());
                UUID sid = UUID.fromString(jwt.getClaimAsString("sid"));
                boolean valid = jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(Instant.now()) && jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM access_session s JOIN app_user u ON u.id = s.user_id
                        WHERE s.id = :sid AND s.user_id = :user AND s.revoked_at IS NULL
                        AND s.expires_at > now() AND u.status = 'ACTIVE')
                        """).param("sid", sid).param("user", user).query(Boolean.class).single();
                boolean staff = jdbc.sql("SELECT EXISTS(SELECT 1 FROM user_role WHERE user_id=:id AND role_code<>'MEMBER')")
                        .param("id",user).query(Boolean.class).single();
                if(staff && (jwt.getClaimAsStringList("amr")==null || !jwt.getClaimAsStringList("amr").contains("totp"))) valid=false;
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException | NullPointerException ex) { /* invalid claim, never log token */ }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Phiên không còn hiệu lực", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("gowithbae"), session));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeProblem(ObjectMapper json, jakarta.servlet.http.HttpServletResponse response, int status)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), java.util.Map.of("status", status, "title",
                status == 401 ? "Cần đăng nhập hoặc phiên đã hết hạn" : "Không có quyền thực hiện thao tác này",
                "traceId", UUID.randomUUID().toString()));
    }
}
