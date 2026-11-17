package vn.gowithbae.platform;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.auth.DatabaseJwtAuthenticationConverter;

@Component
public class CurrentUser {
    private final DatabaseJwtAuthenticationConverter converter;

    public CurrentUser(DatabaseJwtAuthenticationConverter converter) { this.converter = converter; }

    public Optional<UUID> optionalId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        try { return Optional.of(UUID.fromString(jwt.getSubject())); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên không hợp lệ"); }
    }

    public UUID id() { return optionalId().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập")); }

    public UUID sessionId() {
        id();
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try { return UUID.fromString(jwt.getClaimAsString("sid")); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên không hợp lệ"); }
    }

    public void requireActive() {
        authorized();
    }

    public void requireRole(String role) {
        requireAnyRole(role);
    }

    public void requireAnyRole(String... roles) {
        var authentication=authorized();
        boolean allowed=authentication.getAuthorities().stream()
                .anyMatch(authority->java.util.Arrays.stream(roles).anyMatch(role->authority.getAuthority().equals("ROLE_"+role)));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thực hiện thao tác này");
    }

    private org.springframework.security.authentication.AbstractAuthenticationToken authorized(){
        id();
        Jwt jwt=(Jwt)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try {return converter.convert(jwt);}
        catch(org.springframework.security.core.AuthenticationException ex){
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Phiên không còn hiệu lực");
        }
    }
}
