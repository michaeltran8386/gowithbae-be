package vn.gowithbae.choice;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.ChoiceSessionsApiDelegate;
import vn.gowithbae.api.model.CastVoteRequest;
import vn.gowithbae.api.model.ChoiceSession;
import vn.gowithbae.api.model.ChoiceSessionCreated;
import vn.gowithbae.api.model.CreateChoiceSessionRequest;
import vn.gowithbae.api.model.DatePlan;
import vn.gowithbae.api.model.FinalizeChoiceSessionRequest;
import vn.gowithbae.api.model.ShareGrantCreated;

/** REST delegate cho nhóm API phiên cùng chọn, bỏ phiếu, cấp/thu hồi link mời và chốt địa điểm. */
@Service
public class ChoiceSessionsDelegate implements ChoiceSessionsApiDelegate {

    private final ChoiceSessionService choiceSessionService;

    public ChoiceSessionsDelegate(ChoiceSessionService choiceSessionService) {
        this.choiceSessionService = choiceSessionService;
    }

    @Override
    public ResponseEntity<List<ChoiceSession>> listMyChoiceSessions() {
        return ResponseEntity.ok(choiceSessionService.listMyChoiceSessions());
    }

    @Override
    public ResponseEntity<ChoiceSessionCreated> createChoiceSession(
            CreateChoiceSessionRequest createChoiceSessionRequest,
            String idempotencyKey) {
        return choiceSessionService.createChoiceSession(createChoiceSessionRequest, idempotencyKey);
    }

    @Override
    public ResponseEntity<ChoiceSession> getChoiceSession(UUID sessionId) {
        return ResponseEntity.ok(choiceSessionService.getChoiceSession(sessionId));
    }

    @Override
    public ResponseEntity<Void> castVote(UUID sessionId, UUID candidateId, CastVoteRequest castVoteRequest) {
        choiceSessionService.castVote(sessionId, candidateId, castVoteRequest);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DatePlan> finalizeChoiceSession(
            UUID sessionId,
            FinalizeChoiceSessionRequest finalizeChoiceSessionRequest,
            String idempotencyKey) {
        return choiceSessionService.finalizeChoiceSession(sessionId, finalizeChoiceSessionRequest, idempotencyKey);
    }

    @Override
    public ResponseEntity<ShareGrantCreated> createShareGrant(UUID sessionId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(choiceSessionService.createShareGrant(sessionId));
    }

    @Override
    public ResponseEntity<Void> revokeShareGrant(UUID sessionId, UUID grantId) {
        choiceSessionService.revokeShareGrant(sessionId, grantId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> removeRecipient(UUID sessionId) {
        choiceSessionService.removeRecipient(sessionId);
        return ResponseEntity.noContent().build();
    }
}
