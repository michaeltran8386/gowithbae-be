package vn.gowithbae.common.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import vn.gowithbae.common.web.Problems;

/**
 * Cầu nối tĩnh sang {@link SecurityContextHolder} đã được {@code DatabaseJwtAuthenticationConverter} (phần A) xác thực từ DB.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** Id người dùng; gọi ở endpoint bắt buộc đăng nhập (SecurityConfig đã chặn 401 khi không có token). */
    public static UUID id() {
        try {
            return UUID.fromString(jwt().getSubject());
        } catch (IllegalArgumentException e) {
            throw Problems.forbidden("Phiên đăng nhập không hợp lệ");
        }
    }

    /** Đọc quyền hiện tại từ DB authorities do filter A nạp; chỉ fallback claim nếu test chưa đi qua converter. */
    public static Set<String> roles() {
        Authentication auth = authentication();
        Set<String> fromAuthorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        if (!fromAuthorities.isEmpty()) {
            return fromAuthorities;
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return roles == null ? Set.of() : Set.copyOf(roles);
        }
        return Set.of();
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
        Authentication auth = authentication();
        if (!(auth.getPrincipal() instanceof Jwt jwt)) {
            throw Problems.forbidden("Cần đăng nhập");
        }
        return jwt;
    }

    private static Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt)) {
            throw Problems.forbidden("Cần đăng nhập");
        }
        return auth;
    }
}
