package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;
import vn.gowithbae.admin.AdminPermissions;
import vn.gowithbae.api.model.CreateChoiceSessionRequest;
import vn.gowithbae.api.model.DatePlanWrite;
import vn.gowithbae.api.model.JoinShareGrantRequest;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.PlaceReportCreate;
import vn.gowithbae.api.model.PlaceReportReason;
import vn.gowithbae.api.model.SupportTicketCreate;
import vn.gowithbae.choice.ChoiceSessionService;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.UserPrincipal;
import vn.gowithbae.common.IdempotencyService;
import vn.gowithbae.common.PageUtil;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.place.PlaceQueryServiceImpl;
import vn.gowithbae.place.PlaceReader;
import vn.gowithbae.plan.DatePlanService;
import vn.gowithbae.plan.IcsCalendarFormatter;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.NotificationService;
import vn.gowithbae.platform.PlaceQueryService;
import vn.gowithbae.platform.RequestRateLimiter;
import vn.gowithbae.report.PlaceReportService;
import vn.gowithbae.shared.PlaceCatalogService;
import vn.gowithbae.shared.StoredFileLookup;
import vn.gowithbae.support.SupportService;

/** Kiểm tra biên tham số và tiện ích phân trang của Người 3 trên platform của Người A (không cần DB/Docker). */
class PersonCServiceStateTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-12-20T10:00:00Z"), TimeHelper.VN_ZONE);

    @Test
    void pageUtilClampsBoundsAndComputesTotalPages() {
        assertThat(PageUtil.sanitizePage(-3)).isZero();
        assertThat(PageUtil.sanitizeSize(null)).isEqualTo(20);
        assertThat(PageUtil.sanitizeSize(0)).isEqualTo(1);
        assertThat(PageUtil.sanitizeSize(500)).isEqualTo(100);

        PageMeta empty = PageUtil.buildMeta(0, 20, 0L);
        assertThat(empty.getTotalPages()).isZero();

        PageMeta twoPages = PageUtil.buildMeta(0, 20, 21L);
        assertThat(twoPages.getTotalPages()).isEqualTo(2);
    }

    @Test
    void choiceSessionRejectsInvalidCandidateCountBeforeQueryingDb() {
        ActorResolver actorResolver = mock(ActorResolver.class);
        when(actorResolver.requireActiveUser()).thenReturn(new UserPrincipal(UUID.randomUUID(), "Minh", "ACTIVE"));
        IdempotencyService passThroughIdempotency = new IdempotencyService(mock(JdbcClient.class), JsonMapper.builder().build());

        ChoiceSessionService service = new ChoiceSessionService(
                mock(JdbcClient.class),
                actorResolver,
                mock(PlaceCatalogService.class),
                passThroughIdempotency,
                mock(DatePlanService.class),
                FIXED_CLOCK);

        CreateChoiceSessionRequest tooFew = new CreateChoiceSessionRequest();
        tooFew.addPlaceIdsItem(UUID.randomUUID());

        assertThatThrownBy(() -> service.createChoiceSession(tooFew, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> service.joinShareGrant("token-placeholder", new JoinShareGrantRequest("   ")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void directDatePlanRejectsPastOrCurrentScheduledAt() {
        ActorResolver actorResolver = mock(ActorResolver.class);
        when(actorResolver.requireActiveUser()).thenReturn(new UserPrincipal(UUID.randomUUID(), "Minh", "ACTIVE"));
        IdempotencyService passThroughIdempotency = new IdempotencyService(mock(JdbcClient.class), JsonMapper.builder().build());

        DatePlanService service = new DatePlanService(
                mock(JdbcClient.class),
                actorResolver,
                mock(PlaceCatalogService.class),
                passThroughIdempotency,
                new IcsCalendarFormatter(),
                FIXED_CLOCK);

        DatePlanWrite pastWrite = new DatePlanWrite(
                UUID.randomUUID(),
                java.time.OffsetDateTime.now(FIXED_CLOCK));

        assertThatThrownBy(() -> service.createDirectPlan(pastWrite, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void supportCreateRejectsBlankSubjectOrBody() {
        ActorResolver actorResolver = mock(ActorResolver.class);
        when(actorResolver.requireActiveUser()).thenReturn(new UserPrincipal(UUID.randomUUID(), "Minh", "ACTIVE"));

        SupportService service = new SupportService(
                mock(JdbcClient.class),
                actorResolver,
                mock(StoredFileLookup.class),
                mock(AuditService.class),
                mock(NotificationService.class),
                mock(PlatformTransactionManager.class));

        SupportTicketCreate blankSubject = new SupportTicketCreate(
                SupportTicketCreate.CategoryEnum.OTHER,
                "   ",
                "Nội dung hợp lệ");

        assertThatThrownBy(() -> service.createTicket(blankSubject))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void officialPlatformContractsAlignAcrossMemberAAndB() {
        UUID placeId = UUID.randomUUID();

        // 1. PlaceCatalogService ẩn thông tin nhạy cảm cho địa điểm HIDDEN/INACTIVE khớp PROP OQ-11 của B
        PlaceCard redacted = PlaceCatalogService.unavailableCard(placeId, "Quán Đã Ẩn");
        assertThat(redacted.getId()).isEqualTo(placeId);
        assertThat(redacted.getName()).isEqualTo("Quán Đã Ẩn");
        assertThat(redacted.getPrice().getStatus()).isEqualTo(KnownStatus.UNKNOWN);
        assertThat(redacted.getAreaName()).isNull();
        assertThat(redacted.getCoverUrl()).isNull();
        assertThat(redacted.getTags()).isEmpty();

        // 2. Xuất .ics an toàn khi địa chỉ null (địa điểm đã bị ẩn)
        String ics = new IcsCalendarFormatter().formatEvent(
                UUID.randomUUID(),
                "Quán Đã Ẩn",
                null,
                OffsetDateTime.now(FIXED_CLOCK).plusDays(1),
                OffsetDateTime.now(FIXED_CLOCK),
                null,
                false);
        assertThat(ics).contains("SUMMARY:Hẹn tại Quán Đã Ẩn");
        assertThat(ics).doesNotContain("LOCATION:");

        // 3. PlaceReportService dùng chính thức PlaceQueryService của A+B
        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(placeQueryService).requirePublished(placeId);
        PlaceReportService reportService = new PlaceReportService(
                mock(JdbcClient.class),
                mock(ActorResolver.class),
                placeQueryService,
                mock(StoredFileLookup.class),
                mock(AuditService.class),
                mock(RequestRateLimiter.class));
        assertThatThrownBy(() -> reportService.submitReport(placeId, new PlaceReportCreate(PlaceReportReason.OTHER)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(placeQueryService).requirePublished(placeId);

        // 4. Bean AdminPermissions của B gọi thẳng platform CurrentUser của A
        CurrentUser platformUser = mock(CurrentUser.class);
        new AdminPermissions(platformUser).requireContentRead();
        verify(platformUser).requireAnyRole("EDITOR", "REVIEWER", "SUPER_ADMIN");

        // 5. AuditService của A chặn field riêng tư, PlaceQueryServiceImpl của B từ chối null bằng 404
        var realAudit = new AuditService(mock(JdbcClient.class), JsonMapper.builder().build());
        assertThatThrownBy(() -> realAudit.log(UUID.randomUUID(), "TEST", "place", placeId.toString(), null,
                Map.of("notes", "secret"), null))
                .isInstanceOf(IllegalArgumentException.class);

        var placeQueryImpl = new PlaceQueryServiceImpl(mock(JdbcClient.class), mock(PlaceReader.class));
        assertThatThrownBy(() -> placeQueryImpl.requirePublished(null))
                .isInstanceOf(ErrorResponseException.class)
                .extracting(ex -> ((ErrorResponseException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> placeQueryImpl.getSavedPlace(null))
                .isInstanceOf(ErrorResponseException.class)
                .extracting(ex -> ((ErrorResponseException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
