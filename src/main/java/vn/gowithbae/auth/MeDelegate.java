package vn.gowithbae.auth;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.MeApiDelegate;
import vn.gowithbae.api.model.PlaceVisitCount;
import vn.gowithbae.api.model.RequestEmailChangeRequest;
import vn.gowithbae.api.model.User;
import vn.gowithbae.api.model.UserUpdate;
import vn.gowithbae.api.model.VerifyEmailChangeRequest;
import vn.gowithbae.me.VisitCountService;
import vn.gowithbae.platform.AccountLifecycleParticipant;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.FileService;

@Service
public class MeDelegate implements MeApiDelegate {
    private final CurrentUser current;
    private final JdbcClient jdbc;
    private final UserAccounts users;
    private final AuthService auth;
    private final FileService files;
    private final ObjectProvider<AccountLifecycleParticipant> lifecycle;
    private ObjectProvider<VisitCountService> visitCount;

    public MeDelegate(CurrentUser current,JdbcClient jdbc,UserAccounts users,AuthService auth,FileService files,
            ObjectProvider<AccountLifecycleParticipant> lifecycle){
        this.current=current;this.jdbc=jdbc;this.users=users;this.auth=auth;this.files=files;this.lifecycle=lifecycle;
    }

    @Autowired
    void setVisitCount(ObjectProvider<VisitCountService> visitCount) {
        this.visitCount = visitCount;
    }

    @Override
    public ResponseEntity<PlaceVisitCount> getMyPlaceVisitCount(UUID placeId) {
        VisitCountService service = visitCount == null ? null : visitCount.getIfAvailable();
        if (service == null) {
            return MeApiDelegate.super.getMyPlaceVisitCount(placeId);
        }
        return ResponseEntity.ok(service.getMyPlaceVisitCount(placeId));
    }
    @Override public ResponseEntity<User> getMe(){return ResponseEntity.ok(users.get(current.id()));}

    @Override @Transactional public ResponseEntity<User> updateMe(UserUpdate body){
        current.requireActive();
        users.requireActiveLocked(current.id());
        if(body.getDisplayName()!=null){
            String name=body.getDisplayName().strip();
            if(name.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tên hiển thị không được trống");
            jdbc.sql("UPDATE app_user SET display_name=:name,updated_at=now() WHERE id=:id")
                    .param("name",name).param("id",current.id()).update();
        }
        if(body.getAvatarFileId()!=null){
            files.requireOwned(body.getAvatarFileId(),current.id(),"AVATAR");
            jdbc.sql("UPDATE app_user SET avatar_file_id=:file,updated_at=now() WHERE id=:id")
                    .param("file",body.getAvatarFileId()).param("id",current.id()).update();
        }
        return ResponseEntity.ok(users.get(current.id()));
    }
    @Override public ResponseEntity<Void> requestEmailChange(RequestEmailChangeRequest body){
        current.requireActive();auth.request(body.getNewEmail(),"EMAIL_CHANGE",current.id());return ResponseEntity.accepted().build();
    }
    @Override public ResponseEntity<User> verifyEmailChange(VerifyEmailChangeRequest body){
        current.requireActive();return ResponseEntity.ok(auth.verifyEmailChange(current.id(),body.getCode()));
    }
    @Override @Transactional public ResponseEntity<Void> requestAccountDeletion(){
        var id=current.id();
        jdbc.sql("SELECT pg_advisory_xact_lock(220022)").query(rs->{ });
        auth.lockForAccountMutation(id);
        var account=users.get(id);
        if(account.getRoles().contains(vn.gowithbae.api.model.Role.SUPER_ADMIN)) {
            long admins=jdbc.sql("SELECT count(*) FROM app_user u JOIN user_role r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='SUPER_ADMIN'")
                    .query(Long.class).single();
            if(admins<=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Không thể xóa quản trị cuối cùng");
        }
        jdbc.sql("UPDATE app_user SET status='DELETION_REQUESTED',updated_at=now() WHERE id=:id").param("id",id).update();
        jdbc.sql("UPDATE access_session SET revoked_at=now() WHERE user_id=:id AND revoked_at IS NULL").param("id",id).update();
        jdbc.sql("UPDATE login_challenge SET used_at=now() WHERE used_at IS NULL AND (user_id=:id OR email=:email)")
                .param("id",id).param("email",account.getEmail()).update();
        lifecycle.orderedStream().forEach(participant->participant.revokeAccess(id));
        return ResponseEntity.accepted().build(); // Nhận yêu cầu; không xóa phá dữ liệu/lịch sử trước chính sách retention.
    }
}
