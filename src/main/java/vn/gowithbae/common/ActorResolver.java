package vn.gowithbae.common;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/** Đọc danh tính Bearer JWT / X-Recipient-Session / fingerprint ẩn danh từ request hiện hành. */
@Component
public class ActorResolver {

    public static final String RECIPIENT_SESSION_HEADER = "X-Recipient-Session";

    private final JdbcClient jdbc;
    private final String inviteBaseUrl;

    public ActorResolver(JdbcClient jdbc, @Value("${app.invite.base-url:}") String inviteBaseUrl) {
        this.jdbc = jdbc;
        this.inviteBaseUrl = inviteBaseUrl == null ? "" : inviteBaseUrl.trim();
    }

    public record UserPrincipal(UUID userId, String displayName, String status) {
        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    public record RecipientPrincipal(
            UUID recipientSessionId,
            UUID shareGrantId,
            UUID choiceSessionId,
            String displayName,
            boolean grantRevoked,
            boolean recipientRevoked) {

        public boolean isValidAccess() {
            return !grantRevoked && !recipientRevoked;
        }
    }

    /** Bắt buộc thành viên đã đăng nhập, phiên chưa thu hồi và tài khoản chưa khóa/yêu cầu xóa (RULE-17). */
    public UserPrincipal requireActiveUser() {
        UserPrincipal user = findAuthenticatedUser(true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập hoặc phiên hết hạn"));
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đang bị khóa hoặc chờ xóa");
        }
        return user;
    }

    /** Trả Optional user nếu request mang Bearer JWT; kiểm tra phiên thu hồi và trạng thái xóa. */
    public Optional<UserPrincipal> findAuthenticatedUser(boolean failOnInvalidToken) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return Optional.empty();
        }
        Jwt jwt = jwtAuth.getToken();
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ex) {
            if (failOnInvalidToken) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token không hợp lệ");
            }
            return Optional.empty();
        }

        String sidClaim = jwt.getClaimAsString("sid");
        if (sidClaim != null && !sidClaim.isBlank()) {
            UUID sid;
            try {
                sid = UUID.fromString(sidClaim);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ");
            }
            Boolean validSession = jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM access_session
                        WHERE id = :sid AND user_id = :uid AND revoked_at IS NULL AND expires_at > now()
                    )
                    """)
                    .param("sid", sid)
                    .param("uid", userId)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(validSession)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn hoặc bị thu hồi");
            }
        }

        Optional<UserPrincipal> user = jdbc.sql("""
                SELECT id, display_name, status
                FROM app_user
                WHERE id = :uid
                """)
                .param("uid", userId)
                .query((rs, rowNum) -> new UserPrincipal(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("status")))
                .optional();

        if (user.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại");
        }
        if ("DELETION_REQUESTED".equals(user.get().status())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản đã yêu cầu xóa");
        }
        return user;
    }

    /** Đọc phiên người nhận từ header X-Recipient-Session và kiểm tra cả trạng thái grant nguồn (RULE-12). */
    public Optional<RecipientPrincipal> findRecipientPrincipal() {
        Optional<HttpServletRequest> reqOpt = currentRequest();
        if (reqOpt.isEmpty()) {
            return Optional.empty();
        }
        String rawToken = reqOpt.get().getHeader(RECIPIENT_SESSION_HEADER);
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = TokenHashUtil.sha256Hex(rawToken.trim());
        return jdbc.sql("""
                SELECT rs.id AS rs_id, rs.share_grant_id, rs.session_id, rs.display_name,
                       (sg.revoked_at IS NOT NULL) AS grant_revoked,
                       (rs.revoked_at IS NOT NULL) AS recipient_revoked
                FROM recipient_session rs
                JOIN share_grant sg ON sg.id = rs.share_grant_id
                WHERE rs.token_hash = :hash
                """)
                .param("hash", tokenHash)
                .query((rs, rowNum) -> new RecipientPrincipal(
                        rs.getObject("rs_id", UUID.class),
                        rs.getObject("share_grant_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getBoolean("grant_revoked"),
                        rs.getBoolean("recipient_revoked")))
                .optional()
                .filter(RecipientPrincipal::isValidAccess);
    }

    /** Băm IP + User-Agent cho chống lạm dụng báo sai ẩn danh (không lưu IP thô). */
    public String resolveClientFingerprint() {
        return currentRequest().map(req -> {
            String forwarded = req.getHeader("X-Forwarded-For");
            String ip = (forwarded != null && !forwarded.isBlank())
                    ? forwarded.split(",")[0].trim()
                    : String.valueOf(req.getRemoteAddr());
            String ua = String.valueOf(req.getHeader("User-Agent"));
            return TokenHashUtil.sha256Hex(ip + "|" + ua);
        }).orElseGet(() -> TokenHashUtil.sha256Hex("unknown"));
    }

    public String buildInviteUrl(String inviteToken) {
        if (!inviteBaseUrl.isEmpty()) {
            String base = inviteBaseUrl.endsWith("/") ? inviteBaseUrl : inviteBaseUrl + "/";
            return base + inviteToken;
        }
        return currentRequest()
                .map(req -> ServletUriComponentsBuilder.fromContextPath(req)
                        .path("/invite/")
                        .path(inviteToken)
                        .toUriString())
                .orElse("https://gowithbae.vn/invite/" + inviteToken);
    }

    private Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }
}
