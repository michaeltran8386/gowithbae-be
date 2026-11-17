package vn.gowithbae.choice;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.ShareGrantsApiDelegate;
import vn.gowithbae.api.model.JoinShareGrantRequest;
import vn.gowithbae.api.model.RecipientSessionToken;
import vn.gowithbae.api.model.ShareGrantPreview;

/** REST delegate cho link mời công khai (xem trước không cần đăng nhập và tham gia chiếm suất). */
@Service
public class ShareGrantsDelegate implements ShareGrantsApiDelegate {

    private final ChoiceSessionService choiceSessionService;

    public ShareGrantsDelegate(ChoiceSessionService choiceSessionService) {
        this.choiceSessionService = choiceSessionService;
    }

    @Override
    public ResponseEntity<ShareGrantPreview> previewShareGrant(String inviteToken) {
        return ResponseEntity.ok(choiceSessionService.previewShareGrant(inviteToken));
    }

    @Override
    public ResponseEntity<RecipientSessionToken> joinShareGrant(
            String inviteToken,
            JoinShareGrantRequest joinShareGrantRequest) {
        return ResponseEntity.ok(choiceSessionService.joinShareGrant(inviteToken, joinShareGrantRequest));
    }
}
