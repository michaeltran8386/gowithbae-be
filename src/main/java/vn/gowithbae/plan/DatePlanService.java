package vn.gowithbae.plan;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.DatePlan;
import vn.gowithbae.api.model.DatePlanStatus;
import vn.gowithbae.api.model.DatePlanUpdate;
import vn.gowithbae.api.model.DatePlanWrite;
import vn.gowithbae.api.model.DateReminder;
import vn.gowithbae.api.model.DateReminderAction;
import vn.gowithbae.api.model.DateReminderState;
import vn.gowithbae.api.model.DateReminderStatus;
import vn.gowithbae.api.model.ReportVisitRequest;
import vn.gowithbae.api.model.Warning;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.RecipientPrincipal;
import vn.gowithbae.common.ActorResolver.UserPrincipal;
import vn.gowithbae.common.IdempotencyService;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.shared.PlaceQueryService;
import vn.gowithbae.shared.PlaceQueryService.OpeningStatus;
import vn.gowithbae.shared.PlaceQueryService.PlaceSummary;

/** Nghiệp vụ kế hoạch buổi hẹn, lời nhắc Đi chưa?, tự xác nhận đã đi và xuất lịch (FR-15..18, CR-001). */
@Service
public class DatePlanService {

    private final JdbcClient jdbc;
    private final ActorResolver actorResolver;
    private final PlaceQueryService placeQueryService;
    private final IdempotencyService idempotencyService;
    private final IcsCalendarFormatter icsFormatter;
    private final Clock clock;

    public DatePlanService(
            JdbcClient jdbc,
            ActorResolver actorResolver,
            PlaceQueryService placeQueryService,
            IdempotencyService idempotencyService,
            IcsCalendarFormatter icsFormatter,
            Clock clock) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.placeQueryService = placeQueryService;
        this.idempotencyService = idempotencyService;
        this.icsFormatter = icsFormatter;
        this.clock = clock;
    }

    public List<DatePlan> listMyPlans(@Nullable DatePlanStatus status) {
        UserPrincipal user = actorResolver.requireActiveUser();
        List<PlanRow> rows = jdbc.sql("""
                SELECT dp.id, dp.owner_id, dp.place_id, dp.scheduled_at, dp.note, dp.status,
                       dp.choice_session_id, dp.recipient_session_id, dp.created_at, dp.updated_at,
                       vsr.answer AS visit_answer
                FROM date_plan dp
                LEFT JOIN visit_self_report vsr
                       ON vsr.plan_id = dp.id AND vsr.respondent_user_id = dp.owner_id
                WHERE dp.owner_id = :ownerId
                  AND (CAST(:status AS text) IS NULL OR dp.status = CAST(:status AS text))
                ORDER BY dp.scheduled_at DESC
                """)
                .param("ownerId", user.userId())
                .param("status", status == null ? null : status.getValue())
                .query((rs, rowNum) -> mapPlanRow(rs))
                .list();

        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Set<UUID> placeIds = rows.stream().map(PlanRow::placeId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, PlaceSummary> places = placeQueryService.findPlaces(placeIds);
        return rows.stream()
                .map(row -> toDto(row, places.get(row.placeId()), DatePlan.ViewerRoleEnum.OWNER))
                .toList();
    }

    @Transactional
    public ResponseEntity<DatePlan> createDirectPlan(DatePlanWrite req, @Nullable String idempotencyKey) {
        UserPrincipal owner = actorResolver.requireActiveUser();
        return idempotencyService.execute(
                owner.userId().toString(),
                "createDatePlan",
                idempotencyKey,
                req,
                DatePlan.class,
                () -> {
                    DatePlan created = createPlanInternal(
                            owner.userId(),
                            req.getPlaceId(),
                            req.getScheduledAt(),
                            req.getNote(),
                            null,
                            null);
                    return ResponseEntity.status(HttpStatus.CREATED).body(created);
                });
    }

    /** Tạo kế hoạch trong cùng transaction với thao tác chốt phiên cùng chọn (FR-13, OQ-08). */
    @Transactional
    public DatePlan createPlanForFinalizedSession(
            UUID ownerId,
            UUID placeId,
            OffsetDateTime scheduledAt,
            @Nullable String note,
            UUID choiceSessionId,
            @Nullable UUID recipientSessionId) {
        return createPlanInternal(ownerId, placeId, scheduledAt, note, choiceSessionId, recipientSessionId);
    }

    private DatePlan createPlanInternal(
            UUID ownerId,
            UUID placeId,
            OffsetDateTime scheduledAt,
            @Nullable String note,
            @Nullable UUID choiceSessionId,
            @Nullable UUID recipientSessionId) {

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (scheduledAt == null || !scheduledAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời điểm buổi hẹn phải ở tương lai");
        }
        PlaceSummary place = placeQueryService.findPlace(placeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại"));
        if (!place.isPublished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Địa điểm hiện không khả dụng để lên kế hoạch");
        }
        OpeningStatus openingStatus = placeQueryService.evaluateOpeningAt(placeId, place.hoursStatus(), scheduledAt);
        if (openingStatus == OpeningStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Địa điểm đóng cửa vào thời điểm đã chọn");
        }

        String normalizedNote = normalizeNote(note);
        PlanRow inserted = jdbc.sql("""
                INSERT INTO date_plan(owner_id, place_id, scheduled_at, note, status, choice_session_id, recipient_session_id)
                VALUES (:ownerId, :placeId, :scheduledAt, CAST(:note AS text), 'SCHEDULED',
                        CAST(:choiceSessionId AS uuid), CAST(:recipientSessionId AS uuid))
                RETURNING id, owner_id, place_id, scheduled_at, note, status, choice_session_id, recipient_session_id, created_at, updated_at,
                          NULL::text AS visit_answer
                """)
                .param("ownerId", ownerId)
                .param("placeId", placeId)
                .param("scheduledAt", scheduledAt)
                .param("note", normalizedNote)
                .param("choiceSessionId", choiceSessionId)
                .param("recipientSessionId", recipientSessionId)
                .query((rs, rowNum) -> mapPlanRow(rs))
                .single();

        return toDto(inserted, place, DatePlan.ViewerRoleEnum.OWNER);
    }

    public DatePlan getPlan(UUID planId) {
        ViewerPlanAccess access = resolveViewerAccess(planId);
        PlaceSummary place = placeQueryService.findPlace(access.plan().placeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại"));
        return toDto(access.plan(), place, access.viewerRole());
    }

    @Transactional
    public DatePlan updatePlan(UUID planId, DatePlanUpdate req) {
        UserPrincipal owner = actorResolver.requireActiveUser();
        PlanRow plan = lockOwnedPlan(planId, owner.userId());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (!"SCHEDULED".equals(plan.status()) || !plan.scheduledAt().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chỉ được chỉnh sửa kế hoạch ở trạng thái đã lên lịch và chưa qua giờ hẹn");
        }

        UUID targetPlaceId = req.getPlaceId() != null ? req.getPlaceId() : plan.placeId();
        boolean placeChanged = !targetPlaceId.equals(plan.placeId());
        PlaceSummary place = placeQueryService.findPlace(targetPlaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại"));
        if (placeChanged && !place.isPublished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể đổi sang địa điểm đang bị ẩn hoặc ngừng hoạt động");
        }

        OffsetDateTime targetScheduledAt = req.getScheduledAt() != null ? req.getScheduledAt() : plan.scheduledAt();
        boolean timeChanged = req.getScheduledAt() != null && !req.getScheduledAt().isEqual(plan.scheduledAt());
        if (timeChanged && !targetScheduledAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời điểm buổi hẹn mới phải ở tương lai");
        }
        if (placeChanged || timeChanged) {
            OpeningStatus opening = placeQueryService.evaluateOpeningAt(targetPlaceId, place.hoursStatus(), targetScheduledAt);
            if (opening == OpeningStatus.CLOSED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Địa điểm đóng cửa vào thời điểm đã chọn");
            }
        }

        String updatedNote = req.getNote() != null ? normalizeNote(req.getNote()) : plan.note();
        PlanRow updated = jdbc.sql("""
                UPDATE date_plan
                SET place_id = :placeId,
                    scheduled_at = :scheduledAt,
                    note = CAST(:note AS text),
                    updated_at = now()
                WHERE id = :planId
                RETURNING id, owner_id, place_id, scheduled_at, note, status, choice_session_id, recipient_session_id, created_at, updated_at,
                          (SELECT answer FROM visit_self_report WHERE plan_id = :planId AND respondent_user_id = :ownerId) AS visit_answer
                """)
                .param("placeId", targetPlaceId)
                .param("scheduledAt", targetScheduledAt)
                .param("note", updatedNote)
                .param("planId", planId)
                .param("ownerId", owner.userId())
                .query((rs, rowNum) -> mapPlanRow(rs))
                .single();

        // OQ-25 (đã chốt với user): đổi giờ mới sau khi đã DISMISS sẽ mở lại lời nhắc về PENDING (không reset nếu đã ANSWERED).
        if (timeChanged) {
            jdbc.sql("""
                    UPDATE date_reminder
                    SET scheduled_at = :scheduledAt,
                        status = 'PENDING',
                        dismissed_at = NULL,
                        updated_at = now()
                    WHERE plan_id = :planId
                      AND recipient_user_id = :ownerId
                      AND status IN ('PENDING', 'SHOWN', 'DISMISSED')
                    """)
                    .param("scheduledAt", targetScheduledAt)
                    .param("planId", planId)
                    .param("ownerId", owner.userId())
                    .update();
        }

        return toDto(updated, place, DatePlan.ViewerRoleEnum.OWNER);
    }

    @Transactional
    public DatePlan cancelPlan(UUID planId) {
        UserPrincipal owner = actorResolver.requireActiveUser();
        PlanRow plan = lockOwnedPlan(planId, owner.userId());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (!"SCHEDULED".equals(plan.status()) || !plan.scheduledAt().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chỉ được hủy kế hoạch chưa qua giờ hẹn và chưa bị hủy");
        }

        PlanRow updated = jdbc.sql("""
                UPDATE date_plan
                SET status = 'CANCELLED', updated_at = now()
                WHERE id = :planId
                RETURNING id, owner_id, place_id, scheduled_at, note, status, choice_session_id, recipient_session_id, created_at, updated_at,
                          (SELECT answer FROM visit_self_report WHERE plan_id = :planId AND respondent_user_id = :ownerId) AS visit_answer
                """)
                .param("planId", planId)
                .param("ownerId", owner.userId())
                .query((rs, rowNum) -> mapPlanRow(rs))
                .single();

        jdbc.sql("""
                UPDATE date_reminder
                SET status = 'CANCELLED',
                    dismissed_at = NULL,
                    updated_at = now()
                WHERE plan_id = :planId
                  AND status IN ('PENDING', 'SHOWN', 'DISMISSED')
                """)
                .param("planId", planId)
                .update();

        PlaceSummary place = placeQueryService.findPlace(updated.placeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại"));
        return toDto(updated, place, DatePlan.ViewerRoleEnum.OWNER);
    }

    public String formatCalendar(UUID planId) {
        ViewerPlanAccess access = resolveViewerAccess(planId);
        PlaceSummary place = placeQueryService.findPlace(access.plan().placeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại"));
        return icsFormatter.formatEvent(
                access.plan().id(),
                place.name(),
                place.address(),
                access.plan().scheduledAt(),
                OffsetDateTime.now(clock),
                access.plan().note(),
                "CANCELLED".equals(access.plan().status()));
    }

    @Transactional
    public void reportVisit(UUID planId, ReportVisitRequest req) {
        ViewerPlanAccess access = resolveViewerAccess(planId);
        if (access.viewerRole() != DatePlan.ViewerRoleEnum.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ chủ kế hoạch mới có quyền xác nhận lượt đi");
        }
        UserPrincipal owner = actorResolver.requireActiveUser();
        PlanRow plan = lockOwnedPlan(planId, owner.userId());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if ("CANCELLED".equals(plan.status()) || plan.scheduledAt().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chỉ được xác nhận lượt đi khi đã đến giờ hẹn và kế hoạch chưa hủy");
        }

        String requestedAnswer = req.getAnswer() == ReportVisitRequest.AnswerEnum.TRUE ? "YES" : "NO";
        Optional<String> existingAnswer = jdbc.sql("""
                SELECT answer FROM visit_self_report
                WHERE plan_id = :planId AND respondent_user_id = :ownerId
                FOR UPDATE
                """)
                .param("planId", planId)
                .param("ownerId", owner.userId())
                .query(String.class)
                .optional();

        if (existingAnswer.isPresent()) {
            if (!existingAnswer.get().equals(requestedAnswer)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể sửa câu trả lời sau khi đã xác nhận");
            }
            upsertReminderAnswered(planId, owner.userId(), plan.scheduledAt());
            return;
        }

        jdbc.sql("""
                INSERT INTO visit_self_report(plan_id, respondent_user_id, answer)
                VALUES (:planId, :ownerId, :answer)
                """)
                .param("planId", planId)
                .param("ownerId", owner.userId())
                .param("answer", requestedAnswer)
                .update();

        upsertReminderAnswered(planId, owner.userId(), plan.scheduledAt());
    }

    public DateReminderState getDateReminder(UUID planId) {
        ViewerPlanAccess access = resolveViewerAccess(planId);
        if (access.viewerRole() != DatePlan.ViewerRoleEnum.OWNER) {
            return new DateReminderState(null, false, false);
        }

        PlanRow plan = access.plan();
        Optional<DateReminder> reminderOpt = findOwnerReminder(planId, plan.ownerId());
        boolean hasVisitReport = plan.ownerVisitAnswer() != null;
        OffsetDateTime now = OffsetDateTime.now(clock);

        boolean eligibleStatus = reminderOpt.isEmpty()
                || reminderOpt.get().getStatus() == DateReminderStatus.PENDING
                || reminderOpt.get().getStatus() == DateReminderStatus.SHOWN;
        boolean dueNow = !"CANCELLED".equals(plan.status()) && !plan.scheduledAt().isAfter(now);
        boolean shouldShow = dueNow && !hasVisitReport && eligibleStatus;

        return new DateReminderState(reminderOpt.orElse(null), shouldShow, shouldShow);
    }

    @Transactional
    public DateReminderState respondToReminder(UUID planId, DateReminderAction req) {
        ViewerPlanAccess access = resolveViewerAccess(planId);
        if (access.viewerRole() != DatePlan.ViewerRoleEnum.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ chủ kế hoạch mới có quyền xử lý lời nhắc");
        }
        UserPrincipal owner = actorResolver.requireActiveUser();
        PlanRow plan = lockOwnedPlan(planId, owner.userId());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if ("CANCELLED".equals(plan.status()) || plan.scheduledAt().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chỉ được thao tác lời nhắc khi đã đến giờ hẹn và kế hoạch chưa hủy");
        }

        Optional<DateReminder> existingReminder = findOwnerReminderForUpdate(planId, owner.userId());
        Optional<String> existingVisit = jdbc.sql("""
                SELECT answer FROM visit_self_report
                WHERE plan_id = :planId AND respondent_user_id = :ownerId
                FOR UPDATE
                """)
                .param("planId", planId)
                .param("ownerId", owner.userId())
                .query(String.class)
                .optional();

        if (req.getAction() == DateReminderAction.ActionEnum.DISMISS) {
            if (existingVisit.isPresent()
                    || (existingReminder.isPresent() && existingReminder.get().getStatus() == DateReminderStatus.ANSWERED)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lời nhắc đã được xác nhận, không thể bỏ qua");
            }
            if (existingReminder.isPresent() && existingReminder.get().getStatus() == DateReminderStatus.DISMISSED) {
                return new DateReminderState(existingReminder.get(), false, false);
            }
            DateReminder saved = upsertReminderDismissed(planId, owner.userId(), plan.scheduledAt());
            return new DateReminderState(saved, false, false);
        }

        // CONFIRM_VISIT
        if (existingVisit.isPresent() && !"YES".equals(existingVisit.get())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể đổi câu trả lời đã ghi nhận");
        }
        if (existingReminder.isPresent() && existingReminder.get().getStatus() == DateReminderStatus.DISMISSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lời nhắc đã bị bỏ qua trước đó");
        }
        if (existingVisit.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO visit_self_report(plan_id, respondent_user_id, answer)
                    VALUES (:planId, :ownerId, 'YES')
                    """)
                    .param("planId", planId)
                    .param("ownerId", owner.userId())
                    .update();
        }
        DateReminder answered = upsertReminderAnswered(planId, owner.userId(), plan.scheduledAt());
        return new DateReminderState(answered, false, false);
    }

    private DateReminder upsertReminderDismissed(UUID planId, UUID ownerId, OffsetDateTime scheduledAt) {
        return jdbc.sql("""
                INSERT INTO date_reminder(plan_id, recipient_user_id, scheduled_at, status, dismissed_at)
                VALUES (:planId, :ownerId, :scheduledAt, 'DISMISSED', now())
                ON CONFLICT (plan_id, recipient_user_id) WHERE recipient_user_id IS NOT NULL
                DO UPDATE SET status = 'DISMISSED',
                              dismissed_at = now(),
                              answered_at = NULL,
                              scheduled_at = EXCLUDED.scheduled_at,
                              updated_at = now()
                RETURNING id, plan_id, scheduled_at, status, dismissed_at, answered_at
                """)
                .param("planId", planId)
                .param("ownerId", ownerId)
                .param("scheduledAt", scheduledAt)
                .query((rs, rowNum) -> mapReminder(rs))
                .single();
    }

    private DateReminder upsertReminderAnswered(UUID planId, UUID ownerId, OffsetDateTime scheduledAt) {
        return jdbc.sql("""
                INSERT INTO date_reminder(plan_id, recipient_user_id, scheduled_at, status, answered_at)
                VALUES (:planId, :ownerId, :scheduledAt, 'ANSWERED', now())
                ON CONFLICT (plan_id, recipient_user_id) WHERE recipient_user_id IS NOT NULL
                DO UPDATE SET status = 'ANSWERED',
                              dismissed_at = NULL,
                              answered_at = COALESCE(date_reminder.answered_at, now()),
                              scheduled_at = EXCLUDED.scheduled_at,
                              updated_at = now()
                RETURNING id, plan_id, scheduled_at, status, dismissed_at, answered_at
                """)
                .param("planId", planId)
                .param("ownerId", ownerId)
                .param("scheduledAt", scheduledAt)
                .query((rs, rowNum) -> mapReminder(rs))
                .single();
    }

    private Optional<DateReminder> findOwnerReminder(UUID planId, UUID ownerId) {
        return jdbc.sql("""
                SELECT id, plan_id, scheduled_at, status, dismissed_at, answered_at
                FROM date_reminder
                WHERE plan_id = :planId AND recipient_user_id = :ownerId
                """)
                .param("planId", planId)
                .param("ownerId", ownerId)
                .query((rs, rowNum) -> mapReminder(rs))
                .optional();
    }

    private Optional<DateReminder> findOwnerReminderForUpdate(UUID planId, UUID ownerId) {
        return jdbc.sql("""
                SELECT id, plan_id, scheduled_at, status, dismissed_at, answered_at
                FROM date_reminder
                WHERE plan_id = :planId AND recipient_user_id = :ownerId
                FOR UPDATE
                """)
                .param("planId", planId)
                .param("ownerId", ownerId)
                .query((rs, rowNum) -> mapReminder(rs))
                .optional();
    }

    private ViewerPlanAccess resolveViewerAccess(UUID planId) {
        Optional<UserPrincipal> userOpt = actorResolver.findAuthenticatedUser(false);
        Optional<RecipientPrincipal> recipientOpt = actorResolver.findRecipientPrincipal();

        Optional<PlanRow> planOpt = jdbc.sql("""
                SELECT dp.id, dp.owner_id, dp.place_id, dp.scheduled_at, dp.note, dp.status,
                       dp.choice_session_id, dp.recipient_session_id, dp.created_at, dp.updated_at,
                       vsr.answer AS visit_answer
                FROM date_plan dp
                LEFT JOIN visit_self_report vsr
                       ON vsr.plan_id = dp.id AND vsr.respondent_user_id = dp.owner_id
                WHERE dp.id = :planId
                """)
                .param("planId", planId)
                .query((rs, rowNum) -> mapPlanRow(rs))
                .optional();

        if (planOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
        }
        PlanRow plan = planOpt.get();
        if (userOpt.isPresent() && userOpt.get().userId().equals(plan.ownerId())) {
            return new ViewerPlanAccess(plan, DatePlan.ViewerRoleEnum.OWNER);
        }
        if (recipientOpt.isPresent()
                && plan.recipientSessionId() != null
                && plan.recipientSessionId().equals(recipientOpt.get().recipientSessionId())) {
            return new ViewerPlanAccess(plan, DatePlan.ViewerRoleEnum.RECIPIENT);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch");
    }

    private PlanRow lockOwnedPlan(UUID planId, UUID ownerId) {
        return jdbc.sql("""
                SELECT dp.id, dp.owner_id, dp.place_id, dp.scheduled_at, dp.note, dp.status,
                       dp.choice_session_id, dp.recipient_session_id, dp.created_at, dp.updated_at,
                       (SELECT answer FROM visit_self_report WHERE plan_id = dp.id AND respondent_user_id = dp.owner_id) AS visit_answer
                FROM date_plan dp
                WHERE dp.id = :planId AND dp.owner_id = :ownerId
                FOR UPDATE
                """)
                .param("planId", planId)
                .param("ownerId", ownerId)
                .query((rs, rowNum) -> mapPlanRow(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch"));
    }

    private DatePlan toDto(PlanRow row, @Nullable PlaceSummary place, DatePlan.ViewerRoleEnum viewerRole) {
        if (place == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại");
        }
        boolean placeAvailable = place.isPublished();
        List<Warning> warnings = new ArrayList<>();
        if (!placeAvailable) {
            warnings.add(new Warning("PLACE_UNAVAILABLE", "Địa điểm hiện đang tạm ẩn hoặc ngừng hoạt động."));
        } else if ("UNKNOWN".equals(place.hoursStatus())) {
            warnings.add(new Warning("HOURS_UNKNOWN", "Địa điểm chưa rõ giờ mở cửa, vui lòng kiểm tra lại trước khi đi."));
        }

        DatePlan.VisitAnswerEnum visitAnswer = viewerRole == DatePlan.ViewerRoleEnum.OWNER
                ? mapVisitAnswer(row.ownerVisitAnswer())
                : null;

        return new DatePlan(
                row.id(),
                place.card(),
                row.scheduledAt(),
                DatePlanStatus.fromValue(row.status()),
                viewerRole,
                Boolean.FALSE,
                row.createdAt(),
                row.updatedAt())
                .placeAvailable(placeAvailable)
                .note(row.note())
                .choiceSessionId(row.choiceSessionId())
                .visitAnswer(visitAnswer)
                .warnings(warnings);
    }

    private DatePlan.VisitAnswerEnum mapVisitAnswer(@Nullable String answer) {
        if ("YES".equals(answer)) {
            return DatePlan.VisitAnswerEnum.TRUE;
        }
        if ("NO".equals(answer)) {
            return DatePlan.VisitAnswerEnum.FALSE;
        }
        return null;
    }

    private DateReminder mapReminder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DateReminder(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                TimeHelper.getOffsetDateTime(rs, "scheduled_at"),
                DateReminderStatus.fromValue(rs.getString("status")))
                .dismissedAt(TimeHelper.getOffsetDateTime(rs, "dismissed_at"))
                .answeredAt(TimeHelper.getOffsetDateTime(rs, "answered_at"));
    }

    private PlanRow mapPlanRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanRow(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("place_id", UUID.class),
                TimeHelper.getOffsetDateTime(rs, "scheduled_at"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getObject("choice_session_id", UUID.class),
                rs.getObject("recipient_session_id", UUID.class),
                TimeHelper.getOffsetDateTime(rs, "created_at"),
                TimeHelper.getOffsetDateTime(rs, "updated_at"),
                rs.getString("visit_answer"));
    }

    private @Nullable String normalizeNote(@Nullable String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PlanRow(
            UUID id,
            UUID ownerId,
            UUID placeId,
            OffsetDateTime scheduledAt,
            @Nullable String note,
            String status,
            @Nullable UUID choiceSessionId,
            @Nullable UUID recipientSessionId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            @Nullable String ownerVisitAnswer) {}

    private record ViewerPlanAccess(PlanRow plan, DatePlan.ViewerRoleEnum viewerRole) {}
}
