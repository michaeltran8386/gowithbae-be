package vn.gowithbae.plan;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.DatePlansApiDelegate;
import vn.gowithbae.api.model.DatePlan;
import vn.gowithbae.api.model.DatePlanStatus;
import vn.gowithbae.api.model.DatePlanUpdate;
import vn.gowithbae.api.model.DatePlanWrite;
import vn.gowithbae.api.model.DateReminder;
import vn.gowithbae.api.model.DateReminderAction;
import vn.gowithbae.api.model.DateReminderState;
import vn.gowithbae.api.model.DateReminderStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.ReportVisitRequest;
import vn.gowithbae.place.PlacesDelegate;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.PlaceQueryService;

@Service
public class DatePlansDelegate implements DatePlansApiDelegate {

    private static final DateTimeFormatter ICS_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final PlaceQueryService places;
    private final PlacesDelegate placesDelegate;

    public DatePlansDelegate(JdbcClient jdbc, CurrentUser current, PlaceQueryService places, PlacesDelegate placesDelegate) {
        this.jdbc = jdbc;
        this.current = current;
        this.places = places;
        this.placesDelegate = placesDelegate;
    }

    @Override
    @Transactional
    public ResponseEntity<DatePlan> createDatePlan(DatePlanWrite write, String idempotencyKey) {
        current.requireActive();
        UUID userId = current.id();

        if (write.getPlaceId() == null || write.getScheduledAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu địa điểm hoặc thời gian hẹn");
        }

        places.requirePublished(write.getPlaceId());

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO date_plan (id, owner_id, place_id, scheduled_at, note, status, created_at, updated_at)
                VALUES (:id, :ownerId, :placeId, :scheduledAt, :note, 'SCHEDULED', :now, :now)
                """)
                .param("id", id)
                .param("ownerId", userId)
                .param("placeId", write.getPlaceId())
                .param("scheduledAt", write.getScheduledAt())
                .param("note", write.getNote())
                .param("now", now)
                .update();

        PlaceCard card = placesDelegate.fetchPlaceCard(write.getPlaceId());

        DatePlan plan = new DatePlan(
                id,
                card,
                write.getScheduledAt(),
                DatePlanStatus.SCHEDULED,
                DatePlan.ViewerRoleEnum.OWNER,
                false,
                now,
                now
        ).note(write.getNote()).placeAvailable(true);

        return ResponseEntity.status(HttpStatus.CREATED).body(plan);
    }

    @Override
    public ResponseEntity<List<DatePlan>> listDatePlans(DatePlanStatus status) {
        current.requireActive();
        UUID userId = current.id();

        String sql = """
                SELECT dp.id, dp.place_id, dp.scheduled_at, dp.note, dp.status, dp.choice_session_id,
                       dp.created_at, dp.updated_at,
                       r.answer AS visit_answer
                FROM date_plan dp
                LEFT JOIN visit_self_report r ON r.plan_id = dp.id AND r.respondent_user_id = :userId
                WHERE dp.owner_id = :userId
                """ + (status != null ? " AND dp.status = :status" : "") + """
                ORDER BY dp.scheduled_at DESC
                """;

        var spec = jdbc.sql(sql).param("userId", userId);
        if (status != null) {
            spec = spec.param("status", status.getValue());
        }

        List<DatePlan> list = spec.query((rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID placeId = rs.getObject("place_id", UUID.class);
            PlaceCard card = placesDelegate.fetchPlaceCard(placeId);
            OffsetDateTime scheduledAt = rs.getObject("scheduled_at", OffsetDateTime.class);
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            String note = rs.getString("note");
            String statStr = rs.getString("status");
            UUID csId = rs.getObject("choice_session_id", UUID.class);
            String visitAns = rs.getString("visit_answer");

            DatePlan p = new DatePlan(
                    id,
                    card,
                    scheduledAt,
                    DatePlanStatus.fromValue(statStr),
                    DatePlan.ViewerRoleEnum.OWNER,
                    false,
                    createdAt,
                    updatedAt
            ).note(note).placeAvailable(card != null).choiceSessionId(csId);

            if (visitAns != null) {
                p.setVisitAnswer(DatePlan.VisitAnswerEnum.fromValue(visitAns.toLowerCase()));
            }

            return p;
        }).list();

        return ResponseEntity.ok(list);
    }

    @Override
    public ResponseEntity<DatePlan> getDatePlan(UUID planId) {
        current.requireActive();
        UUID userId = current.id();

        var planOpt = jdbc.sql("""
                SELECT dp.id, dp.place_id, dp.scheduled_at, dp.note, dp.status, dp.choice_session_id,
                       dp.created_at, dp.updated_at,
                       r.answer AS visit_answer
                FROM date_plan dp
                LEFT JOIN visit_self_report r ON r.plan_id = dp.id AND r.respondent_user_id = :userId
                WHERE dp.id = :planId AND dp.owner_id = :userId
                """)
                .param("planId", planId)
                .param("userId", userId)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID placeId = rs.getObject("place_id", UUID.class);
                    PlaceCard card = placesDelegate.fetchPlaceCard(placeId);
                    OffsetDateTime scheduledAt = rs.getObject("scheduled_at", OffsetDateTime.class);
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
                    String note = rs.getString("note");
                    String statStr = rs.getString("status");
                    UUID csId = rs.getObject("choice_session_id", UUID.class);
                    String visitAns = rs.getString("visit_answer");

                    DatePlan p = new DatePlan(
                            id,
                            card,
                            scheduledAt,
                            DatePlanStatus.fromValue(statStr),
                            DatePlan.ViewerRoleEnum.OWNER,
                            false,
                            createdAt,
                            updatedAt
                    ).note(note).placeAvailable(card != null).choiceSessionId(csId);

                    if (visitAns != null) {
                        p.setVisitAnswer(DatePlan.VisitAnswerEnum.fromValue(visitAns.toLowerCase()));
                    }

                    return p;
                })
                .optional();

        return planOpt.map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch"));
    }

    @Override
    @Transactional
    public ResponseEntity<DatePlan> updateDatePlan(UUID planId, DatePlanUpdate update) {
        current.requireActive();
        UUID userId = current.id();

        var existing = jdbc.sql("SELECT place_id, status FROM date_plan WHERE id = :id AND owner_id = :userId")
                .param("id", planId).param("userId", userId)
                .query((rs, i) -> rs.getString("status")).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch"));

        if ("CANCELLED".equals(existing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kế hoạch đã hủy không thể cập nhật");
        }

        if (update.getPlaceId() != null) {
            places.requirePublished(update.getPlaceId());
        }

        jdbc.sql("""
                UPDATE date_plan
                SET scheduled_at = coalesce(:scheduledAt, scheduled_at),
                    place_id = coalesce(:placeId, place_id),
                    note = coalesce(:note, note),
                    updated_at = now()
                WHERE id = :id AND owner_id = :userId
                """)
                .param("scheduledAt", update.getScheduledAt())
                .param("placeId", update.getPlaceId())
                .param("note", update.getNote())
                .param("id", planId)
                .param("userId", userId)
                .update();

        return getDatePlan(planId);
    }

    @Override
    @Transactional
    public ResponseEntity<DatePlan> cancelDatePlan(UUID planId) {
        current.requireActive();
        UUID userId = current.id();

        int rows = jdbc.sql("UPDATE date_plan SET status = 'CANCELLED', updated_at = now() WHERE id = :id AND owner_id = :userId")
                .param("id", planId).param("userId", userId).update();

        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
        }

        return getDatePlan(planId);
    }

    @Override
    public ResponseEntity<String> downloadDatePlanCalendar(UUID planId) {
        current.requireActive();
        DatePlan plan = getDatePlan(planId).getBody();
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
        }

        OffsetDateTime start = plan.getScheduledAt().atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime end = start.plusHours(2);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        String placeName = plan.getPlace() != null ? plan.getPlace().getName() : "Địa điểm hẹn";
        String summary = "Hẹn hò tại " + placeName;
        String note = plan.getNote() != null ? plan.getNote().replace("\n", "\\n") : "";

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n")
           .append("VERSION:2.0\r\n")
           .append("PRODID:-//GoWithBae//DatePlan//VI\r\n")
           .append("CALSCALE:GREGORIAN\r\n")
           .append("METHOD:PUBLISH\r\n")
           .append("BEGIN:VEVENT\r\n")
           .append("UID:").append(plan.getId()).append("@gowithbae.vn\r\n")
           .append("DTSTAMP:").append(now.format(ICS_TIME_FORMAT)).append("\r\n")
           .append("DTSTART:").append(start.format(ICS_TIME_FORMAT)).append("\r\n")
           .append("DTEND:").append(end.format(ICS_TIME_FORMAT)).append("\r\n")
           .append("SUMMARY:").append(summary).append("\r\n")
           .append("DESCRIPTION:").append(note).append("\r\n")
           .append("STATUS:CONFIRMED\r\n")
           .append("END:VEVENT\r\n")
           .append("END:VCALENDAR\r\n");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .body(ics.toString());
    }

    @Override
    @Transactional
    public ResponseEntity<Void> reportVisit(UUID planId, ReportVisitRequest req) {
        current.requireActive();
        UUID userId = current.id();

        boolean exists = jdbc.sql("SELECT count(*) FROM date_plan WHERE id = :id AND owner_id = :userId")
                .param("id", planId).param("userId", userId).query(Long.class).single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
        }

        String ans = req.getAnswer() == ReportVisitRequest.AnswerEnum.TRUE ? "YES" : "NO";

        jdbc.sql("""
                INSERT INTO visit_self_report (plan_id, respondent_user_id, answer, answered_at)
                VALUES (:planId, :userId, :ans, now())
                ON CONFLICT (plan_id, respondent_user_id) WHERE respondent_user_id IS NOT NULL
                DO UPDATE SET answer = EXCLUDED.answer, answered_at = now()
                """)
                .param("planId", planId)
                .param("userId", userId)
                .param("ans", ans)
                .update();

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DateReminderState> getDateReminder(UUID planId) {
        current.requireActive();
        DatePlan plan = getDatePlan(planId).getBody();
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
        }
        DateReminder reminder = new DateReminder(
                UUID.randomUUID(),
                planId,
                plan.getScheduledAt(),
                DateReminderStatus.PENDING
        );
        return ResponseEntity.ok(new DateReminderState(reminder, true, true));
    }

    @Override
    public ResponseEntity<DateReminderState> respondToDateReminder(UUID planId, DateReminderAction action) {
        current.requireActive();
        DatePlan plan = getDatePlan(planId).getBody();
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
        }

        boolean isDismiss = action.getAction() == DateReminderAction.ActionEnum.DISMISS;
        DateReminderStatus status = isDismiss ? DateReminderStatus.DISMISSED : DateReminderStatus.SHOWN;
        DateReminder reminder = new DateReminder(
                UUID.randomUUID(),
                planId,
                plan.getScheduledAt(),
                status
        );

        return ResponseEntity.ok(new DateReminderState(reminder, !isDismiss, true));
    }
}
