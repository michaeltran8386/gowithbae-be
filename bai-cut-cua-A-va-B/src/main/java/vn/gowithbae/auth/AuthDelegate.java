package vn.gowithbae.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.gowithbae.api.AuthApiDelegate;
import vn.gowithbae.api.model.AuthToken;
import vn.gowithbae.api.model.RequestOtpRequest;
import vn.gowithbae.api.model.VerifyOtpRequest;
import vn.gowithbae.platform.CurrentUser;

@Service
public class AuthDelegate implements AuthApiDelegate {
    private final AuthService auth;
    private final CurrentUser current;
    private final JdbcClient jdbc;
    public AuthDelegate(AuthService auth,CurrentUser current,JdbcClient jdbc){this.auth=auth;this.current=current;this.jdbc=jdbc;}
    @Override public ResponseEntity<Void> requestOtp(RequestOtpRequest body){auth.request(body.getEmail(),"LOGIN",null);return ResponseEntity.accepted().build();}
    @Override public ResponseEntity<AuthToken> verifyOtp(VerifyOtpRequest body){return ResponseEntity.ok(auth.login(body.getEmail(),body.getCode(),body.getStaffCode()));}
    @Override public ResponseEntity<Void> logout(){
        jdbc.sql("UPDATE access_session SET revoked_at=now() WHERE id=:sid AND user_id=:id AND revoked_at IS NULL")
                .param("sid",current.sessionId()).param("id",current.id()).update();
        return ResponseEntity.noContent().build();
    }
}
