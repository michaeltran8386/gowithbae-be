package vn.gowithbae.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.MfaActivationLink;
import vn.gowithbae.api.model.MfaActivationPreview;
import vn.gowithbae.common.TokenHashUtil;
import vn.gowithbae.platform.AuditService;

/**
 * Link kích hoạt TOTP dùng 1 lần cho tài khoản quản trị. Chỉ SUPER_ADMIN (qua API quản trị) hoặc lệnh bootstrap
 * tạo được link; không có đường tự cấp MFA từ phiên email. Token chỉ lưu hash, secret mã hóa như staff_mfa.
 */
@Service
public class MfaActivationService {

    static final Duration TTL = Duration.ofHours(24);
    static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final StaffMfa mfa;
    private final AuditService audit;
    private final String webBaseUrl;

    public MfaActivationService(JdbcClient jdbc, StaffMfa mfa, AuditService audit,
            @Value("${app.web.base-url:${app.invite.base-url:http://localhost:5173}}") String webBaseUrl) {
        this.jdbc = jdbc;
        this.mfa = mfa;
        this.audit = audit;
        this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
    }

    /** Tạo link mới, hủy các link chưa dùng trước đó của tài khoản. Tài khoản phải có vai trò quản trị. */
    @Transactional
    public MfaActivationLink issue(UUID userId, UUID createdBy) {
        boolean staff = jdbc.sql("SELECT EXISTS(SELECT 1 FROM user_role WHERE user_id=:id AND role_code<>'MEMBER')")
                .param("id", userId).query(Boolean.class).single();
        if (!staff) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ tài khoản quản trị mới cần kích hoạt TOTP");
        }
        jdbc.sql("UPDATE mfa_activation SET used_at=now() WHERE user_id=:id AND used_at IS NULL").param("id", userId).update();
        String token = TokenHashUtil.generateOpaqueToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(TTL);
        jdbc.sql("""
                INSERT INTO mfa_activation(user_id, token_hash, secret_ciphertext, expires_at, created_by)
                VALUES (:user, :hash, :secret, :expires, :by)
                """)
                .param("user", userId)
                .param("hash", TokenHashUtil.sha256Hex(token))
                .param("secret", mfa.seal(mfa.newSecret()))
                .param("expires", expiresAt)
                .param("by", createdBy)
                .update();
        audit.log(createdBy, "MFA_ACTIVATION_ISSUED", "app_user", userId.toString(), null,
                Map.of("expiresAt", expiresAt.toString()), null);
        return new MfaActivationLink(webBaseUrl + "/admin/activate/" + token, expiresAt);
    }

    @Transactional(readOnly = true)
    public MfaActivationPreview preview(String token) {
        Pending p = find(token, false);
        String issuer = "GoWithBae";
        String label = URLEncoder.encode(issuer + ":" + p.email(), StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + p.secret() + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=6&period=30";
        return new MfaActivationPreview(p.email(), uri, p.secret(), p.expiresAt());
    }

    /** Mã đúng → bật TOTP, thu hồi phiên cũ, link hết hiệu lực. Sai quá {@value #MAX_ATTEMPTS} lần → link hủy. */
    @Transactional(noRollbackFor = ResponseStatusException.class) // giữ lại số lần nhập sai khi trả 400
    public void complete(String token, String code) {
        Pending p = find(token, true);
        long step = StaffMfa.matchingStep(p.secret(), code);
        if (step < 0) {
            jdbc.sql("""
                    UPDATE mfa_activation SET attempts=attempts+1,
                           used_at=CASE WHEN attempts+1>=:max THEN now() ELSE used_at END
                    WHERE id=:id
                    """).param("max", MAX_ATTEMPTS).param("id", p.id()).update();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã xác nhận không đúng");
        }
        mfa.enroll(p.userId(), p.secret());
        mfa.markStepUsed(p.userId(), step);
        jdbc.sql("UPDATE mfa_activation SET used_at=now() WHERE id=:id").param("id", p.id()).update();
        audit.log(p.userId(), "MFA_ENROLLED", "app_user", p.userId().toString(), null, Map.of("mfaEnabled", true),
                "Activation link");
    }

    private record Pending(UUID id, UUID userId, String email, String secret, OffsetDateTime expiresAt) {
    }

    private Pending find(String token, boolean lock) {
        if (token == null || token.isBlank()) {
            throw gone();
        }
        return jdbc.sql("""
                SELECT a.id, a.user_id, u.email, a.secret_ciphertext, a.expires_at
                FROM mfa_activation a JOIN app_user u ON u.id = a.user_id
                WHERE a.token_hash = :hash AND a.used_at IS NULL AND a.expires_at > now() AND u.status = 'ACTIVE'
                """ + (lock ? " FOR UPDATE OF a" : ""))
                .param("hash", TokenHashUtil.sha256Hex(token.trim()))
                .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("email"), mfa.unseal(rs.getString("secret_ciphertext")),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(MfaActivationService::gone);
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE, "Link kích hoạt không hợp lệ, đã dùng hoặc đã hết hạn");
    }
}
