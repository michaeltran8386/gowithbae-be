package vn.gowithbae.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.model.AuthToken;
import vn.gowithbae.api.model.User;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.platform.RequestRateLimiter;
import vn.gowithbae.platform.SecretHash;

@Service
@EnableConfigurationProperties(AuthPolicy.class)
public class AuthService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuthPolicy policy;
    private final RequestRateLimiter limiter;
    private final SecretHash hash;
    private final OtpDelivery delivery;
    private final JwtEncoder encoder;
    private final UserAccounts users;
    private final StaffMfa mfa;
    private final SecureRandom random=new SecureRandom();

    public AuthService(JdbcClient jdbc,TransactionTemplate tx,AuthPolicy policy,RequestRateLimiter limiter,
            SecretHash hash,OtpDelivery delivery,JwtEncoder encoder,UserAccounts users,StaffMfa mfa) {
        this.jdbc=jdbc;this.tx=tx;this.policy=policy;this.limiter=limiter;this.hash=hash;
        this.delivery=delivery;this.encoder=encoder;this.users=users;this.mfa=mfa;
    }

    private record Challenge(UUID id,String email,UUID userId,String codeHash,int attempts,
            OffsetDateTime expiresAt,OffsetDateTime createdAt,OffsetDateTime usedAt) {}
    private record Verification(User user,UUID sessionId,OffsetDateTime expiresAt,HttpStatus error,String errorCode) {
        Verification(User user,UUID sessionId,OffsetDateTime expiresAt,HttpStatus error){this(user,sessionId,expiresAt,error,null);}
    }

    public void request(String rawEmail,String purpose,UUID userId) {
        String email=normalizeEmail(rawEmail);
        if(!limiter.consume("otp-request-ip",peer(),policy.ipRequestsPerHour(),Duration.ofHours(1))
                || !limiter.consume("otp-request-email",purpose+":"+email,policy.requestsPerHour(),Duration.ofHours(1)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Vượt hạn mức gửi mã");
        tx.executeWithoutResult(status->{
            if(userId!=null) lockAccount(userId);
            lockEmail(email);
            if(userId!=null) users.requireActiveLocked(userId);
            var latest=challenge(email,purpose,userId,false);
            if(latest!=null && latest.createdAt().plus(policy.resendCooldown()).isAfter(OffsetDateTime.now()))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Vui lòng đợi trước khi gửi lại mã");
            if("LOGIN".equals(purpose)) {
                boolean unavailable=jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE lower(email)=:email AND status<>'ACTIVE')")
                        .param("email",email).query(Boolean.class).single();
                if(unavailable) return; // Không tiết lộ tài khoản có tồn tại hoặc bị khóa.
            } else {
                boolean taken=jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE lower(email)=:email AND id<>:id)")
                        .param("email",email).param("id",userId).query(Boolean.class).single();
                if(taken) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email mới không khả dụng");
            }
            UUID id=UUID.randomUUID();
            String code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
            if(userId==null) jdbc.sql("UPDATE login_challenge SET used_at=now() WHERE email=:email AND purpose=:purpose AND used_at IS NULL")
                    .param("email",email).param("purpose",purpose).update();
            else jdbc.sql("UPDATE login_challenge SET used_at=now() WHERE user_id=:id AND purpose=:purpose AND used_at IS NULL")
                    .param("id",userId).param("purpose",purpose).update();
            jdbc.sql("""
                    INSERT INTO login_challenge(id,email,purpose,user_id,code_hash,expires_at,created_at)
                    VALUES (:id,:email,:purpose,:user,:hash,:expiry,clock_timestamp())
                    """).param("id",id).param("email",email).param("purpose",purpose).param("user",userId)
                    .param("hash",hash.digest(id+":"+code)).param("expiry",OffsetDateTime.now().plus(policy.otpTtl())).update();
            delivery.send(email,code,purpose);
        });
    }

    public AuthToken login(String rawEmail,String code,String staffCode) {
        String email=normalizeEmail(rawEmail);
        limitVerification(email);
        Verification result=tx.execute(status->{
            lockEmail(email);
            Challenge c=challenge(email,"LOGIN",null,true);
            HttpStatus error=check(c,code);
            if(error!=null) return new Verification(null,null,null,error);
            UUID id=jdbc.sql("SELECT id FROM app_user WHERE lower(email)=:email FOR UPDATE").param("email",email)
                    .query(UUID.class).optional().orElse(null);
            if(id==null) {
                id=jdbc.sql("INSERT INTO app_user(email,display_name) VALUES (:email,:name) RETURNING id")
                        .param("email",email).param("name","Thành viên").query(UUID.class).single();
                jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES (:id,'MEMBER')").param("id",id).update();
            }
            User user=users.get(id);
            if(user.getStatus()!=vn.gowithbae.api.model.UserStatus.ACTIVE)
                return new Verification(null,null,null,HttpStatus.UNAUTHORIZED);
            if(isStaff(user)) {
                // OTP email đã đúng: báo rõ bước tiếp theo, không tính là nhập sai và chưa tiêu thụ OTP.
                if(!mfa.isEnrolled(id))
                    return new Verification(null,null,null,HttpStatus.UNAUTHORIZED,"MFA_NOT_ENROLLED");
                if(staffCode==null || staffCode.isBlank())
                    return new Verification(null,null,null,HttpStatus.UNAUTHORIZED,"STAFF_CODE_REQUIRED");
                if(!mfa.verify(id,staffCode)) {
                    jdbc.sql("UPDATE login_challenge SET attempts=attempts+1 WHERE id=:id").param("id",c.id()).update();
                    return new Verification(null,null,null,HttpStatus.BAD_REQUEST);
                }
            }
            jdbc.sql("UPDATE login_challenge SET used_at=now() WHERE id=:id").param("id",c.id()).update();
            var expires=OffsetDateTime.now(ZoneOffset.UTC).plus(policy.sessionTtl());
            UUID sid=jdbc.sql("""
                    INSERT INTO access_session(user_id,expires_at,ip_address,user_agent,last_seen_at)
                    VALUES (:id,:expiry,:ip,:agent,now()) RETURNING id
                    """)
                    .param("id",id).param("expiry",expires).param("ip",clientIp()).param("agent",userAgent())
                    .query(UUID.class).single();
            return new Verification(user,sid,expires,null);
        });
        requireSuccess(result);
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("gowithbae").subject(result.user().getId().toString()).issuedAt(now)
                .expiresAt(result.expiresAt().toInstant()).claim("sid",result.sessionId().toString())
                .claim("amr",isStaff(result.user())?java.util.List.of("email_otp","totp"):java.util.List.of("email_otp"))
                .claim("roles",result.user().getRoles().stream().map(Object::toString).toList()).build();
        String jwt=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return new AuthToken(jwt,result.expiresAt(),result.user());
    }

    public User verifyEmailChange(UUID userId,String code) {
        limitVerification(userId.toString());
        Verification result=tx.execute(status->{
            // Giữ cùng thứ tự khóa với gửi mã và login: account → email → user, tránh deadlock.
            lockAccount(userId);
            Challenge pending=challenge(null,"EMAIL_CHANGE",userId,false);
            if(pending==null) return new Verification(null,null,null,HttpStatus.BAD_REQUEST);
            lockEmail(pending.email());
            users.requireActiveLocked(userId);
            Challenge c=challenge(null,"EMAIL_CHANGE",userId,true);
            HttpStatus error=check(c,code);
            if(error!=null) return new Verification(null,null,null,error);
            boolean taken=jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE lower(email)=:email AND id<>:id)")
                    .param("email",c.email()).param("id",userId).query(Boolean.class).single();
            if(taken) return new Verification(null,null,null,HttpStatus.BAD_REQUEST);
            jdbc.sql("UPDATE app_user SET email=:email,updated_at=now() WHERE id=:id AND status='ACTIVE'")
                    .param("email",c.email()).param("id",userId).update();
            jdbc.sql("UPDATE login_challenge SET used_at=now() WHERE id=:id").param("id",c.id()).update();
            return new Verification(users.get(userId),null,null,null);
        });
        requireSuccess(result);
        return result.user();
    }

    private void requireSuccess(Verification result) {
        if("STAFF_CODE_REQUIRED".equals(result.errorCode()))
            throw Problems.status(result.error(),result.errorCode(),"Nhập mã 6 số trong ứng dụng Authenticator");
        if("MFA_NOT_ENROLLED".equals(result.errorCode()))
            throw Problems.status(result.error(),result.errorCode(),"Tài khoản quản trị chưa kích hoạt TOTP; dùng link kích hoạt do quản trị viên cấp");
        if(result.error()!=null) throw new ResponseStatusException(result.error(),
                result.error()==HttpStatus.TOO_MANY_REQUESTS ? "Mã đã vượt số lần thử" : "Mã không hợp lệ hoặc đã hết hiệu lực");
    }

    private boolean isStaff(User user) { return user.getRoles().stream().anyMatch(role->role!=vn.gowithbae.api.model.Role.MEMBER); }

    private HttpStatus check(Challenge c,String code) {
        if(c==null || c.usedAt()!=null || !c.expiresAt().isAfter(OffsetDateTime.now())) return HttpStatus.BAD_REQUEST;
        if(c.attempts()>=policy.maxAttempts()) return HttpStatus.TOO_MANY_REQUESTS;
        if(code==null || !code.matches("[0-9]{6}") || !hash.matches(c.id()+":"+code,c.codeHash())) {
            jdbc.sql("UPDATE login_challenge SET attempts=attempts+1 WHERE id=:id").param("id",c.id()).update();
            return HttpStatus.BAD_REQUEST;
        }
        return null;
    }

    private Challenge challenge(String email,String purpose,UUID userId,boolean forUpdate) {
        return jdbc.sql("SELECT id,email,user_id,code_hash,attempts,expires_at,created_at,used_at FROM login_challenge WHERE purpose=:purpose"
                +(userId==null ? " AND email=:key" : " AND user_id=:key")+" ORDER BY created_at DESC,id DESC LIMIT 1"+(forUpdate?" FOR UPDATE":""))
                .param("purpose",purpose).param("key",userId==null?email:userId)
                .query((rs,n)->new Challenge(rs.getObject("id",UUID.class),rs.getString("email"),rs.getObject("user_id",UUID.class),
                        rs.getString("code_hash"),rs.getInt("attempts"),rs.getObject("expires_at",OffsetDateTime.class),
                        rs.getObject("created_at",OffsetDateTime.class),rs.getObject("used_at",OffsetDateTime.class)))
                .optional().orElse(null);
    }

    private void lockEmail(String email) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key","account:"+email).query(rs->{ });
    }

    private void lockAccount(UUID id) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key","account-id:"+id).query(rs->{ });
    }

    public void lockForAccountMutation(UUID id) {
        lockAccount(id);
        String email=jdbc.sql("SELECT email FROM app_user WHERE id=:id").param("id",id).query(String.class).single();
        lockEmail(email);
        users.requireActiveLocked(id);
    }

    private void limitVerification(String key) {
        if(!limiter.consume("otp-verify-ip",peer(),policy.verifiesPerQuarterHour(),Duration.ofMinutes(15))
                || !limiter.consume("otp-verify",key+":"+peer(),policy.verifiesPerQuarterHour(),Duration.ofMinutes(15)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Vượt hạn mức xác minh");
    }

    public static String normalizeEmail(String email) {
        if(email==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email không hợp lệ");
        String value=email.strip().toLowerCase(Locale.ROOT);
        if(value.length()>254 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email không hợp lệ");
        return value;
    }

    /** IP người dùng cho lịch sử đăng nhập: ưu tiên X-Forwarded-For do reverse proxy (nginx) gắn. */
    private String clientIp() {
        var attrs=RequestContextHolder.getRequestAttributes();
        if(!(attrs instanceof ServletRequestAttributes web)) return null;
        String forwarded=web.getRequest().getHeader("X-Forwarded-For");
        String ip=forwarded!=null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : web.getRequest().getRemoteAddr();
        return ip==null ? null : ip.substring(0,Math.min(ip.length(),64));
    }

    private String userAgent() {
        var attrs=RequestContextHolder.getRequestAttributes();
        if(!(attrs instanceof ServletRequestAttributes web)) return null;
        String agent=web.getRequest().getHeader("User-Agent");
        return agent==null ? null : agent.substring(0,Math.min(agent.length(),300));
    }

    private String peer() {
        var attrs=RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes web ? web.getRequest().getRemoteAddr() : "internal";
    }
}
