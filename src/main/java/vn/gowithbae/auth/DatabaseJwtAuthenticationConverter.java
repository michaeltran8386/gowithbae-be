package vn.gowithbae.auth;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Phiên, quyền hiện tại và điều kiện MFA lấy cùng snapshot, không ghép hai lần đọc có thể lệch nhau. */
@Component
public class DatabaseJwtAuthenticationConverter implements Converter<Jwt,AbstractAuthenticationToken> {
    private final JdbcClient jdbc;
    public DatabaseJwtAuthenticationConverter(JdbcClient jdbc){this.jdbc=jdbc;}

    @Override public AbstractAuthenticationToken convert(Jwt jwt){
        try {
            if(jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(Instant.now()))throw invalid();
            UUID user=UUID.fromString(jwt.getSubject());
            UUID sid=UUID.fromString(jwt.getClaimAsString("sid"));
            boolean mfa=jwt.getClaimAsStringList("amr")!=null && jwt.getClaimAsStringList("amr").contains("totp");
            var authorities=jdbc.sql("""
                    SELECT coalesce(array_agg(r.role_code) FILTER (WHERE r.role_code IS NOT NULL),'{}'::text[]) AS roles
                    FROM access_session s JOIN app_user u ON u.id=s.user_id
                    LEFT JOIN user_role r ON r.user_id=u.id
                    WHERE s.id=:sid AND u.id=:user AND u.status='ACTIVE'
                      AND s.revoked_at IS NULL AND s.expires_at>now()
                    GROUP BY s.id
                    HAVING NOT bool_or(coalesce(r.role_code<>'MEMBER',false)) OR :mfa
                    """).param("sid",sid).param("user",user).param("mfa",mfa)
                    .query((rs,n)->Arrays.stream((String[])rs.getArray("roles").getArray())
                            .map(role->(GrantedAuthority)new SimpleGrantedAuthority("ROLE_"+role)).toList())
                    .optional().orElseThrow(DatabaseJwtAuthenticationConverter::invalid);
            return new JwtAuthenticationToken(jwt,authorities,jwt.getSubject());
        }catch(IllegalArgumentException|NullPointerException ex){throw invalid();}
    }
    private static InvalidBearerTokenException invalid(){return new InvalidBearerTokenException("Phiên hoặc xác thực nhân viên không còn hiệu lực");}
}
