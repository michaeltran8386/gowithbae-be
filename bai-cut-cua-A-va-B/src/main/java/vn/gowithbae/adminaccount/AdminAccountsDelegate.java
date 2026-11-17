package vn.gowithbae.adminaccount;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.AdminUsersApiDelegate;
import vn.gowithbae.api.model.*;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.AccountLifecycleParticipant;

@Service
public class AdminAccountsDelegate implements AdminUsersApiDelegate {
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final AuditService audit;
    private final ObjectProvider<AccountLifecycleParticipant> lifecycle;

    public AdminAccountsDelegate(JdbcClient jdbc, CurrentUser current, AuditService audit,
            ObjectProvider<AccountLifecycleParticipant> lifecycle) {
        this.jdbc = jdbc;
        this.current = current;
        this.audit = audit;
        this.lifecycle = lifecycle;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<AdminUserPage> adminListUsers(String q, UserStatus status, Integer page, Integer size) {
        current.requireActive();
        current.requireRole("SUPER_ADMIN");
        int p = page == null ? 0 : page, s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > 100) throw bad("Invalid pagination");
        Map<String, Object> params = new HashMap<>();
        String where = " WHERE true";
        if (q != null && !q.isBlank()) {
            where += " AND (strpos(lower(email), lower(:q)) > 0 OR strpos(lower(display_name), lower(:q)) > 0)";
            params.put("q", q.trim());
        }
        if (status != null) { where += " AND status = :status"; params.put("status", status.getValue()); }
        long count = jdbc.sql("SELECT count(*) FROM app_user" + where).params(params).query(Long.class).single();
        var users = jdbc.sql("SELECT * FROM app_user" + where + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .params(params).param("limit", s).param("offset", (long) p * s).query(this::mapUser).list();
        return ResponseEntity.ok(new AdminUserPage().items(users)
                .page(new PageMeta(p, s, count, (int) ((count + s - 1) / s))));
    }

    @Override
    @Transactional
    public ResponseEntity<AdminUser> adminLockUser(UUID userId, AdminLockUserRequest request) {
        return changeStatus(userId, request.getReason(), true);
    }

    @Override
    @Transactional
    public ResponseEntity<AdminUser> adminUnlockUser(UUID userId, AdminLockUserRequest request) {
        return changeStatus(userId, request.getReason(), false);
    }

    private ResponseEntity<AdminUser> changeStatus(UUID id, String reason, boolean lock) {
        authorizeMutation();
        reason = reason(reason);
        AdminUser before = lockedUser(id);
        if (lock && id.equals(current.id())) throw conflict("Cannot lock your own account");
        if (before.getStatus() == UserStatus.DELETION_REQUESTED) throw conflict("Deletion is handled separately");
        if (lock) protectLastAdmin(before, false);
        UserStatus status = lock ? UserStatus.LOCKED : UserStatus.ACTIVE;
        jdbc.sql("UPDATE app_user SET status=:status, locked_reason=:reason, updated_at=now() WHERE id=:id")
                .param("status", status.getValue()).param("reason", lock ? reason : null).param("id", id).update();
        if (lock) {
            revoke(id, before.getEmail());
            lifecycle.orderedStream().forEach(participant -> participant.revokeAccess(id));
        }
        audit.log(current.id(), lock ? "USER_LOCKED" : "USER_UNLOCKED", "app_user", id.toString(),
                Map.of("status", before.getStatus().getValue()), Map.of("status", status.getValue()), reason);
        return ResponseEntity.ok(user(id));
    }

    @Override
    @Transactional
    public ResponseEntity<AdminUser> adminSetUserRoles(UUID id, AdminSetUserRolesRequest request) {
        authorizeMutation();
        String reason = reason(request.getReason());
        Set<Role> roles = request.getRoles();
        if (roles == null || roles.contains(null)) throw bad("Invalid roles");
        AdminUser before = lockedUser(id);
        if (before.getStatus() == UserStatus.DELETION_REQUESTED) throw conflict("Deletion is handled separately");
        if (id.equals(current.id()) && !roles.containsAll(before.getRoles())) throw conflict("Cannot demote yourself");
        protectLastAdmin(before, roles.contains(Role.SUPER_ADMIN));
        for (Role role : roles) {
            if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM role WHERE code=:role)").param("role", role.getValue())
                    .query(Boolean.class).single()) throw bad("Unknown role");
        }
        if (!new HashSet<>(before.getRoles()).equals(roles)) {
            jdbc.sql("DELETE FROM user_role WHERE user_id=:id").param("id", id).update();
            for (Role role : roles) jdbc.sql("INSERT INTO user_role(user_id, role_code, granted_by) VALUES (:id,:role,:actor)")
                    .param("id", id).param("role", role.getValue()).param("actor", current.id()).update();
            revoke(id, before.getEmail());
            jdbc.sql("UPDATE app_user SET updated_at=now() WHERE id=:id").param("id", id).update();
            audit.log(current.id(), "USER_ROLES_CHANGED", "app_user", id.toString(),
                    Map.of("roles", before.getRoles().stream().map(Role::getValue).sorted().toList()),
                    Map.of("roles", roles.stream().map(Role::getValue).sorted().toList()), reason);
        }
        return ResponseEntity.ok(user(id));
    }

    private void authorizeMutation() {
        current.requireActive();
        current.requireRole("SUPER_ADMIN");
        // Serialize all admin mutations so simultaneous removals cannot both pass the last-admin count.
        jdbc.sql("SELECT pg_advisory_xact_lock(220022)").query((rs, n) -> true).single();
        lockedUser(current.id());
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user u JOIN user_role r ON r.user_id=u.id "
                + "WHERE u.id=:id AND u.status='ACTIVE' AND r.role_code='SUPER_ADMIN')")
                .param("id", current.id()).query(Boolean.class).single())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active SUPER_ADMIN required");
    }

    private void protectLastAdmin(AdminUser user, boolean keepsSuperAdmin) {
        if (keepsSuperAdmin || user.getStatus() != UserStatus.ACTIVE || !user.getRoles().contains(Role.SUPER_ADMIN)) return;
        long count = jdbc.sql("SELECT count(*) FROM app_user u JOIN user_role r ON r.user_id=u.id "
                + "WHERE u.status='ACTIVE' AND r.role_code='SUPER_ADMIN'").query(Long.class).single();
        if (count <= 1) throw conflict("Cannot remove the last active SUPER_ADMIN");
    }

    private void revoke(UUID id, String email) {
        jdbc.sql("UPDATE access_session SET revoked_at=now() WHERE user_id=:id AND revoked_at IS NULL")
                .param("id", id).update();
        // LOGIN challenges may predate account creation and therefore have no user_id.
        jdbc.sql("UPDATE login_challenge SET used_at=now() WHERE used_at IS NULL AND (user_id=:id OR lower(email)=lower(:email))")
                .param("id", id).param("email", email).update();
    }

    private AdminUser lockedUser(UUID id) {
        // Match AuthService's account -> email -> user order before invalidating OTPs.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "account-id:" + id)
                .query((rs, n) -> true).single();
        String email = jdbc.sql("SELECT email FROM app_user WHERE id=:id").param("id", id).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "account:" + email.toLowerCase(Locale.ROOT))
                .query((rs, n) -> true).single();
        if (jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class).optional().isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        return user(id);
    }

    private AdminUser user(UUID id) {
        return jdbc.sql("SELECT * FROM app_user WHERE id=:id").param("id", id).query(this::mapUser).single();
    }

    private AdminUser mapUser(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Role> roles = jdbc.sql("SELECT role_code FROM user_role WHERE user_id=:id ORDER BY role_code")
                .param("id", id).query(String.class).list().stream().map(Role::fromValue).toList();
        return new AdminUser(id, rs.getString("email"), rs.getString("display_name"), roles,
                UserStatus.fromValue(rs.getString("status"))).createdAt(rs.getObject("created_at", OffsetDateTime.class))
                .lockedReason(rs.getString("locked_reason"));
    }

    private static String reason(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) throw bad("A nonblank reason of at most 1000 characters is required");
        return value.trim();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
