package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.ErrorResponseException;
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
import vn.gowithbae.place.PlaceQueryServiceImpl;
import vn.gowithbae.place.PlaceReader;
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

    @Test
    void unifiedPlatformBridgesEnforceDbAuthoritiesSafeAuditAndPlaceLookup() {
        UUID userId = UUID.randomUUID();
        Jwt staleJwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .claim("roles", List.of("SUPER_ADMIN"))
                .build();
        SecurityContext prev = SecurityContextHolder.getContext();
        try {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new JwtAuthenticationToken(
                    staleJwt,
                    List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")),
                    userId.toString()));
            SecurityContextHolder.setContext(ctx);

            assertThat(vn.gowithbae.common.security.CurrentUser.id()).isEqualTo(userId);
            assertThat(vn.gowithbae.common.security.CurrentUser.roles()).containsExactly("REVIEWER");
            assertThat(vn.gowithbae.common.security.CurrentUser.hasAnyRole("SUPER_ADMIN")).isFalse();
        } finally {
            SecurityContextHolder.setContext(prev);
        }

        AuditService platformAudit = mock(AuditService.class);
        var bridgedAudit = new vn.gowithbae.common.audit.AuditService(platformAudit, JsonMapper.builder().build());
        UUID placeId = UUID.randomUUID();
        bridgedAudit.log(userId, "PLACE_CREATE", "place", placeId, null, Map.of("status", "DRAFT"), "CSV");
        verify(platformAudit).log(userId, "PLACE_CREATE", "place", placeId.toString(), null, Map.of("status", "DRAFT"), "CSV");

        var realAudit = new AuditService(mock(JdbcClient.class), JsonMapper.builder().build());
        var guardedBridge = new vn.gowithbae.common.audit.AuditService(realAudit, JsonMapper.builder().build());
        assertThatThrownBy(() -> guardedBridge.log(userId, "TEST", "place", placeId, null, Map.of("notes", "secret"), null))
                .isInstanceOf(IllegalArgumentException.class);

        var placeQuery = new PlaceQueryServiceImpl(mock(JdbcClient.class), mock(PlaceReader.class));
        assertThatThrownBy(() -> placeQuery.requirePublished(null))
                .isInstanceOf(ErrorResponseException.class)
                .extracting(ex -> ((ErrorResponseException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
