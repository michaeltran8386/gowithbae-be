package vn.gowithbae.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Lớp chặn thô theo URL. Quyền chi tiết (sở hữu, cấp quyền chia sẻ, vai trò kép) kiểm tra trong delegate (FR-22, NFR-04).
 * JWT HS256 do chính backend cấp sau khi xác minh OTP; claim "roles" = danh sách Role, "sid" = access_session.id.
 */
@Configuration
public class SecurityConfig {

    private static final String API = "/api/v1";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable()) // API stateless, token qua header Authorization, không dùng cookie
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                // Công khai: khám phá, tìm, chi tiết, so sánh
                .requestMatchers(HttpMethod.GET, API + "/collections/**", API + "/taxonomies", API + "/areas",
                        API + "/places/**").permitAll()
                // Khách ẩn danh được gửi (có chống lạm dụng trong delegate)
                .requestMatchers(HttpMethod.POST, API + "/places/*/reports", API + "/events", API + "/uploads").permitAll()
                .requestMatchers(API + "/auth/otp/**", API + "/share-grants/**").permitAll()
                // Bearer HOẶC X-Recipient-Session: delegate bắt buộc kiểm tra một trong hai (RULE-12)
                .requestMatchers(HttpMethod.GET, API + "/choice-sessions/*", API + "/date-plans/*",
                        API + "/date-plans/*/calendar.ics").permitAll()
                .requestMatchers(HttpMethod.PUT, API + "/choice-sessions/*/votes/*", API + "/date-plans/*/visit-report").permitAll()
                .requestMatchers(API + "/admin/**").hasAnyRole("EDITOR", "REVIEWER", "SUPPORT", "SUPER_ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(rolesConverter())));
        // shortcut: chưa kiểm tra claim "sid" với access_session.revoked_at; thêm khi làm Auth (FR-22 thu hồi tức thì).
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("app.jwt.secret phải dài tối thiểu 32 byte");
        }
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static JwtAuthenticationConverter rolesConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
