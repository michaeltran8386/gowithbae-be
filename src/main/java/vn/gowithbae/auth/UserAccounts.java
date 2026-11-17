package vn.gowithbae.auth;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.model.Role;
import vn.gowithbae.api.model.User;
import vn.gowithbae.api.model.UserStatus;

@Service
public class UserAccounts {
    private final JdbcClient jdbc;
    public UserAccounts(JdbcClient jdbc) { this.jdbc=jdbc; }

    public User get(UUID id) {
        var roles=jdbc.sql("SELECT role_code FROM user_role WHERE user_id=:id ORDER BY role_code")
                .param("id",id).query(String.class).list().stream().map(Role::fromValue).toList();
        return jdbc.sql("SELECT id,email,display_name,status FROM app_user WHERE id=:id").param("id",id)
                .query((rs,n)->new User(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),
                        roles,UserStatus.fromValue(rs.getString("status"))))
                .optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài khoản"));
    }

    public void requireActiveLocked(UUID id) {
        jdbc.sql("SELECT id FROM app_user WHERE id=:id AND status='ACTIVE' FOR UPDATE").param("id",id)
                .query(UUID.class).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Tài khoản không hoạt động"));
    }
}
