package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;
import vn.gowithbae.api.model.CreateChoiceSessionRequest;
import vn.gowithbae.api.model.DatePlanWrite;
import vn.gowithbae.api.model.JoinShareGrantRequest;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.SupportTicketCreate;
import vn.gowithbae.choice.ChoiceSessionService;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.UserPrincipal;
import vn.gowithbae.common.IdempotencyService;
import vn.gowithbae.common.PageUtil;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.plan.DatePlanService;
import vn.gowithbae.plan.IcsCalendarFormatter;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.NotificationService;
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
}
