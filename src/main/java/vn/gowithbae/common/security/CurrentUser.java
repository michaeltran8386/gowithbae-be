package vn.gowithbae.common.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import vn.gowithbae.common.web.Problems;

/**
 * Người dùng hiện tại lấy từ JWT do backend phát: {@code sub} = app_user.id, {@code roles} = danh sách vai trò.
 * Dùng chung cho mọi module; khi làm phần Auth có thể mở rộng nhưng giữ nguyên chữ ký.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** Id người dùng; gọi ở endpoint bắt buộc đăng nhập (SecurityConfig đã chặn 401 khi không có token). */
    public static UUID id() {
        return UUID.fromString(jwt().getSubject());
    }

    public static Set<String> roles() {
        List<String> roles = jwt().getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static boolean hasAnyRole(String... roles) {
        Set<String> mine = roles();
        for (String role : roles) {
            if (mine.contains(role)) {
                return true;
            }
        }
        return false;
    }

    private static Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw Problems.forbidden("Cần đăng nhập");
        }
        return jwt;
    }
}
