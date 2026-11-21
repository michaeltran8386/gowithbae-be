package vn.gowithbae.plan;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.DatePlansApiDelegate;
import vn.gowithbae.api.model.DatePlan;
import vn.gowithbae.api.model.DatePlanStatus;
import vn.gowithbae.api.model.DatePlanUpdate;
import vn.gowithbae.api.model.DatePlanWrite;
import vn.gowithbae.api.model.DateReminderAction;
import vn.gowithbae.api.model.DateReminderState;
import vn.gowithbae.api.model.PlanInviteCreated;
import vn.gowithbae.api.model.PlanInviteStatus;
import vn.gowithbae.api.model.ReportVisitRequest;

/** REST delegate cho nhóm API kế hoạch buổi hẹn, lịch .ics và lời nhắc Đi chưa?. */
@Service
public class DatePlansDelegate implements DatePlansApiDelegate {

    private static final MediaType TEXT_CALENDAR_UTF8 = new MediaType("text", "calendar", StandardCharsets.UTF_8);

    private final DatePlanService datePlanService;
    private final PlanInviteService planInviteService;

    public DatePlansDelegate(DatePlanService datePlanService, PlanInviteService planInviteService) {
        this.datePlanService = datePlanService;
        this.planInviteService = planInviteService;
    }

    @Override
    public ResponseEntity<PlanInviteCreated> createDatePlanInvite(UUID planId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planInviteService.create(planId));
    }

    @Override
    public ResponseEntity<PlanInviteStatus> getDatePlanInvite(UUID planId) {
        return ResponseEntity.ok(planInviteService.status(planId));
    }

    @Override
    public ResponseEntity<Void> revokeDatePlanInvite(UUID planId) {
        planInviteService.revoke(planId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<DatePlan>> listDatePlans(DatePlanStatus status) {
        return ResponseEntity.ok(datePlanService.listMyPlans(status));
    }

    @Override
    public ResponseEntity<DatePlan> createDatePlan(DatePlanWrite datePlanWrite, String idempotencyKey) {
        return datePlanService.createDirectPlan(datePlanWrite, idempotencyKey);
    }

    @Override
    public ResponseEntity<DatePlan> getDatePlan(UUID planId) {
        return ResponseEntity.ok(datePlanService.getPlan(planId));
    }

    @Override
    public ResponseEntity<DatePlan> updateDatePlan(UUID planId, DatePlanUpdate datePlanUpdate) {
        return ResponseEntity.ok(datePlanService.updatePlan(planId, datePlanUpdate));
    }

    @Override
    public ResponseEntity<DatePlan> cancelDatePlan(UUID planId) {
        return ResponseEntity.ok(datePlanService.cancelPlan(planId));
    }

    @Override
    public ResponseEntity<String> downloadDatePlanCalendar(UUID planId) {
        String ics = datePlanService.formatCalendar(planId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_CALENDAR_UTF8);
        headers.setContentDisposition(ContentDisposition.attachment().filename("date-plan-" + planId + ".ics").build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(ics, headers, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> reportVisit(UUID planId, ReportVisitRequest reportVisitRequest) {
        datePlanService.reportVisit(planId, reportVisitRequest);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DateReminderState> getDateReminder(UUID planId) {
        return ResponseEntity.ok(datePlanService.getDateReminder(planId));
    }

    @Override
    public ResponseEntity<DateReminderState> respondToDateReminder(UUID planId, DateReminderAction dateReminderAction) {
        return ResponseEntity.ok(datePlanService.respondToReminder(planId, dateReminderAction));
    }
}
