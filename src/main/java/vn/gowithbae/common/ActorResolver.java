package vn.gowithbae.common;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.SecretHash;

/**
 * Kết nối CurrentUser / SecretHash của Người A với phiên khách X-Recipient-Session và fingerprint ẩn danh của Người C.
 */
@Component
public class ActorResolver {

    public static final String RECIPIENT_SESSION_HEADER = "X-Recipient-Session";

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final SecretHash secretHash;
    private final String inviteBaseUrl;

    public ActorResolver(
            JdbcClient jdbc,
            CurrentUser currentUser,
            SecretHash secretHash,
            @Value("${app.invite.base-url:}") String inviteBaseUrl) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.secretHash = secretHash;
        this.inviteBaseUrl = inviteBaseUrl == null ? "" : inviteBaseUrl.trim();
    }

    public record UserPrincipal(UUID userId, String displayName, String status) {}

    public record RecipientPrincipal(
            UUID recipientSessionId,
            UUID shareGrantId,
            UUID choiceSessionId,
            String displayName,
            boolean grantRevoked,
            boolean recipientRevoked,
            boolean creatorActive) {

        public boolean isValidAccess() {
            return !grantRevoked && !recipientRevoked && creatorActive;
        }
    }

    /** Bắt buộc thành viên đang ACTIVE, phiên chưa thu hồi và đủ điều kiện MFA qua CurrentUser của Người A. */
    public UserPrincipal requireActiveUser() {
        currentUser.requireActive();
        UUID uid = currentUser.id();
        return loadActiveUser(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không còn hiệu lực"));
    }

    /** Trả Optional user nếu request có Bearer JWT hợp lệ; ngược lại trả empty (cho luồng guest/ẩn danh). */
    public Optional<UserPrincipal> findAuthenticatedUser() {
        Optional<UUID> uidOpt = currentUser.optionalId();
        if (uidOpt.isEmpty()) {
            return Optional.empty();
        }
        currentUser.requireActive();
        return loadActiveUser(uidOpt.get());
    }

    /**
     * Đọc phiên người nhận từ header X-Recipient-Session:
     * kiểm tra cả recipient_session, share_grant nguồn chưa bị thu hồi và chủ tạo vẫn ACTIVE (MEMBER_A.md / RULE-17).
     */
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
                       (rs.revoked_at IS NOT NULL) AS recipient_revoked,
                       (u.status = 'ACTIVE') AS creator_active
                FROM recipient_session rs
                JOIN share_grant sg ON sg.id = rs.share_grant_id
                JOIN choice_session cs ON cs.id = rs.session_id
                JOIN app_user u ON u.id = cs.creator_id
                WHERE rs.token_hash = :hash
                """)
                .param("hash", tokenHash)
                .query((rs, rowNum) -> new RecipientPrincipal(
                        rs.getObject("rs_id", UUID.class),
                        rs.getObject("share_grant_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getBoolean("grant_revoked"),
                        rs.getBoolean("recipient_revoked"),
                        rs.getBoolean("creator_active")))
                .optional()
                .filter(RecipientPrincipal::isValidAccess);
    }

    public String resolveClientPeerKey() {
        return currentRequest().map(req -> {
            String forwarded = req.getHeader("X-Forwarded-For");
            String ip = (forwarded != null && !forwarded.isBlank())
                    ? forwarded.split(",")[0].trim()
                    : String.valueOf(req.getRemoteAddr());
            String ua = String.valueOf(req.getHeader("User-Agent"));
            return ip + "|" + ua;
        }).orElse("unknown");
    }

    public String resolveClientFingerprint() {
        return secretHash.digest("report-fp:" + resolveClientPeerKey());
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

    private Optional<UserPrincipal> loadActiveUser(UUID userId) {
        return jdbc.sql("""
                SELECT id, display_name, status
                FROM app_user
                WHERE id = :uid AND status = 'ACTIVE'
                """)
                .param("uid", userId)
                .query((rs, rowNum) -> new UserPrincipal(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("status")))
                .optional();
    }

    private Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }
}
