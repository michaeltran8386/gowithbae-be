package vn.gowithbae.plan;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.PlanInvitesApiDelegate;
import vn.gowithbae.api.model.PlanInviteRespond;
import vn.gowithbae.api.model.PlanInviteView;

/** Link mời xem kế hoạch, phía người nhận: công khai, không cần tài khoản. */
@Service
public class PlanInvitesDelegate implements PlanInvitesApiDelegate {

    private static final MediaType TEXT_CALENDAR_UTF8 = new MediaType("text", "calendar", StandardCharsets.UTF_8);

    private final PlanInviteService service;

    public PlanInvitesDelegate(PlanInviteService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<PlanInviteView> viewPlanInvite(String inviteToken) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.view(inviteToken));
    }

    @Override
    public ResponseEntity<PlanInviteView> respondPlanInvite(String inviteToken, PlanInviteRespond planInviteRespond) {
        return ResponseEntity.ok(service.respond(inviteToken, planInviteRespond));
    }

    @Override
    public ResponseEntity<String> downloadPlanInviteCalendar(String inviteToken) {
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("buoi-hen.ics").build().toString())
                .body(service.calendar(inviteToken));
    }
}
