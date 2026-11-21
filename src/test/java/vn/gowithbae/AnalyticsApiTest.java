package vn.gowithbae;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.analytics.StatisticsContributor;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="})
@AutoConfigureMockMvc
class AnalyticsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper json;
    @MockitoBean(name = "qualitySourceOne") StatisticsContributor qualityOne;
    @MockitoBean(name = "qualitySourceTwo") StatisticsContributor qualityTwo;
    UUID admin;
    String token;
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    @BeforeEach void seed() {
        jdbc.sql("TRUNCATE app_user, analytics_event CASCADE").update();
        admin = jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('analytics@example.test','Admin') RETURNING id").query(UUID.class).single();
        token = new TestJwtFactory(jdbc, encoder).token(admin, "SUPER_ADMIN");
    }
    Map<String, Object> event(String name, String session, OffsetDateTime at, Map<String, String> props) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", UUID.randomUUID()); e.put("name", name); e.put("sessionId", session);
        e.put("occurredAt", at.toString()); e.put("anonymousId", "guest"); e.put("platform", "WEB_DESKTOP"); e.put("properties", props);
        return e;
    }
    void send(List<Map<String, Object>> events) throws Exception {
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("events", events))))
                .andExpect(status().isAccepted());
    }
    @Test void guestDedupAndPropertyWhitelistAndAuthenticatedIdentity() throws Exception {
        var e = event("place_viewed", "s1", OffsetDateTime.now().minusMinutes(2), Map.of("otp", "123456", "token", "secret", "note", "private"));
        send(List.of(e, e)); send(List.of(e));
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_event").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT props::text FROM analytics_event").query(String.class).single()).isEqualTo("{}");
        var signed = event("place_saved", "s2", OffsetDateTime.now().minusMinutes(1), Map.of());
        signed.remove("anonymousId");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(signed))))).andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT user_id FROM analytics_event WHERE name='place_saved'").query(UUID.class).single()).isEqualTo(admin);
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_event WHERE name='place_saved' AND anonymous_id IS NULL").query(Long.class).single()).isEqualTo(1);
    }
    @Test void invalidLateBatchWritesNothingAndRequiresGuestIdentity() throws Exception {
        var valid = event("place_viewed", "s1", OffsetDateTime.now().minusMinutes(2), Map.of());
        var invalid = event("results_shown", "s1", OffsetDateTime.now().minusMinutes(1), Map.of("results_count", "-1"));
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(valid, invalid))))).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_event").query(Long.class).single()).isZero();
        valid.remove("anonymousId");
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(valid))))).andExpect(status().isBadRequest());
    }
    @Test void timePlatformAndBatchSizeAreValidated() throws Exception {
        var future = event("place_viewed", "s1", OffsetDateTime.now().plusHours(1), Map.of());
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(future))))).andExpect(status().isBadRequest());
        future.put("occurredAt", OffsetDateTime.now().toString()); future.put("platform", "MOBILE_NATIVE");
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(future))))).andExpect(status().isBadRequest());
        var valid = event("place_viewed", "s1", OffsetDateTime.now().minusMinutes(1), Map.of());
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", Collections.nCopies(51, valid))))).andExpect(status().isBadRequest());
    }
    @Test void knownMetricsFiltersAndCsvAgreeWithoutInventedQualityNumbers() throws Exception {
        UUID area = UUID.randomUUID(), other = UUID.randomUUID();
        OffsetDateTime time = LocalDate.now(ZONE).minusDays(1).atTime(10, 0).atZone(ZONE).toOffsetDateTime();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String search = UUID.randomUUID().toString();
            events.add(event("search_submitted", "s" + i, time, Map.of("area_id", area.toString(), "search_id", search)));
            events.add(event("results_shown", "s" + i, time.plusSeconds(1), Map.of("area_id", area.toString(), "search_id", search, "results_count", i < 2 ? "0" : "5")));
        }
        events.add(event("date_plan_created", "s0", time.plusMinutes(10), Map.of("area_id", area.toString())));
        events.add(event("place_viewed", "other", time, Map.of("area_id", other.toString())));
        events.add(events.getFirst()); send(events);
        String day = time.toLocalDate().toString();
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + token)
                .param("from", day).param("to", day).param("areaId", area.toString()).param("platform", "WEB_DESKTOP"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.searches.value").value(10))
                .andExpect(jsonPath("$.zeroResultRate.value").value(0.2))
                .andExpect(jsonPath("$.searchToPlanRate.value").value(0.1))
                .andExpect(jsonPath("$.medianMinutesToPlan.value").value(10))
                .andExpect(jsonPath("$.placeViews.value").value(0))
                .andExpect(jsonPath("$.placesMissingPrice.value").isEmpty())
                .andExpect(jsonPath("$.openPlaceReports.note").value("Không tính được với bộ lọc này"))
                .andExpect(jsonPath("$.retention30d.value").isEmpty());
        String csv = mvc.perform(get("/api/v1/admin/reports/export.csv").header("Authorization", "Bearer " + token)
                .param("from", day).param("to", day).param("areaId", area.toString()).param("platform", "WEB_DESKTOP"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("\"searches\",\"10.0\"", "\"zeroResultRate\",\"0.2\"", "\"placesMissingPrice\",\"\",\"Không tính được với bộ lọc này\"");
        assertThat(csv).doesNotContain("guest", admin.toString());
    }
    @Test void zeroDenominatorUnauthorizedAndInvalidPeriod() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.zeroResultRate.value").isEmpty())
                .andExpect(jsonPath("$.searchToPlanRate.value").isEmpty())
                .andExpect(jsonPath("$.medianMinutesToPlan.value").isEmpty());
        String support = new TestJwtFactory(jdbc, encoder).token(admin, "SUPPORT");
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id AND role_code='SUPER_ADMIN'").param("id", admin).update();
        for (String path : List.of("summary", "export.csv")) {
            mvc.perform(get("/api/v1/admin/reports/" + path).header("Authorization", "Bearer " + support)).andExpect(status().isForbidden());
        }
        String fresh = new TestJwtFactory(jdbc, encoder).token(admin, "SUPER_ADMIN");
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + fresh)
                .param("from", "2026-02-02").param("to", "2026-02-01")).andExpect(status().isBadRequest());
    }

    @Test void matureCohortIncludesReturnsAfterReportPeriodAndYoungCohortsAreUnavailable() throws Exception {
        OffsetDateTime first = LocalDate.now(ZONE).minusDays(40).atTime(12, 0).atZone(ZONE).toOffsetDateTime();
        send(List.of(event("place_viewed", "first", first, Map.of()),
                event("place_viewed", "return", first.plusDays(20), Map.of())));
        String day = first.toLocalDate().toString();
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + token)
                .param("from", day).param("to", day)).andExpect(status().isOk())
                .andExpect(jsonPath("$.retention30d.value").value(1));
        String recent = LocalDate.now(ZONE).minusDays(7).toString();
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + token)
                .param("from", recent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.retention30d.value").isEmpty());
    }

    @Test void vietnamDayBoundariesCategoryPlatformAndInactivityAreRespected() throws Exception {
        OffsetDateTime start = LocalDate.now(ZONE).minusDays(2).atStartOfDay(ZONE).toOffsetDateTime();
        String category = UUID.randomUUID().toString();
        Map<String, String> props = Map.of("category_id", category);
        var wrongPlatform = event("place_viewed", "desktop", start.plusMinutes(1), props);
        wrongPlatform.put("platform", "WEB_MOBILE");
        send(List.of(event("search_submitted", "reused", start, props),
                event("date_plan_created", "reused", start.plusMinutes(31), props),
                event("place_viewed", "before", start.minusSeconds(1), props),
                event("place_viewed", "end", start.plusDays(1), props), wrongPlatform));
        String day = start.toLocalDate().toString();
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + token)
                .param("from", day).param("to", day).param("categoryId", category).param("platform", "WEB_DESKTOP"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.searches.value").value(1))
                .andExpect(jsonPath("$.placeViews.value").value(0))
                .andExpect(jsonPath("$.searchToPlanRate.value").value(0))
                .andExpect(jsonPath("$.medianMinutesToPlan.value").isEmpty())
                .andExpect(jsonPath("$.zeroResultRate.value").isEmpty());
    }

    @Test void contributorsReceiveExactFiltersAndAggregateOnlyKnownCounts() throws Exception {
        LocalDate day = LocalDate.now(ZONE).minusDays(1);
        UUID area = UUID.randomUUID(), category = UUID.randomUUID();
        when(qualityOne.statistics(day, day, area, category, vn.gowithbae.api.model.Platform.WEB_MOBILE))
                .thenReturn(new StatisticsContributor.QualityCounts(2L, null, null, 0L));
        when(qualityTwo.statistics(day, day, area, category, vn.gowithbae.api.model.Platform.WEB_MOBILE))
                .thenReturn(new StatisticsContributor.QualityCounts(3L, 4L, null, null));
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", "Bearer " + token)
                .param("from", day.toString()).param("to", day.toString()).param("areaId", area.toString())
                .param("categoryId", category.toString()).param("platform", "WEB_MOBILE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.placesMissingPrice.value").value(5))
                .andExpect(jsonPath("$.placesMissingHours.value").value(4))
                .andExpect(jsonPath("$.openPlaceReports.value").value(0))
                .andExpect(jsonPath("$.placesVerificationOverdue.value").isEmpty())
                .andExpect(jsonPath("$.placesVerificationOverdue.note").value("Không tính được với bộ lọc này"));
    }
}
