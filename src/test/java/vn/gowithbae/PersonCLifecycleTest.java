package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Các nhánh vòng đời phần C chưa có test: hủy kế hoạch, gỡ người nhận, link hết hạn/thu hồi,
 * hai người cùng join một link, và giờ mở cửa khi lên kế hoạch khớp bộ lọc tìm kiếm của B. Cần Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
class PersonCLifecycleTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder jwtEncoder;

    UUID ownerId;
    String ownerToken;
    String strangerToken;
    UUID placeA;
    UUID placeB;
    UUID overnightBar;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE request_rate_limit, idempotency_record, audit_log, notification, date_reminder, visit_self_report, date_plan, vote, recipient_session, share_grant, choice_candidate, choice_session, saved_list_item, saved_list, opening_hours, date_exception, place_price, place_tag, place_image, stored_file, place, area, access_session, app_user CASCADE").update();
        ownerId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('owner@test.invalid', 'Minh') RETURNING id")
                .query(UUID.class).single();
        UUID strangerId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('other@test.invalid', 'Khác') RETURNING id")
                .query(UUID.class).single();
        var tokens = new TestJwtFactory(jdbc, jwtEncoder);
        ownerToken = "Bearer " + tokens.token(ownerId, "MEMBER");
        strangerToken = "Bearer " + tokens.token(strangerId, "MEMBER");

        UUID areaId = jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1') RETURNING id").query(UUID.class).single();
        placeA = place("PA", "Quán A", areaId);
        placeB = place("PB", "Quán B", areaId);
        overnightBar = place("BAR", "Bar Đêm", areaId);
        for (int dow = 1; dow <= 7; dow++) {
            hours(placeA, dow, "08:00", "23:00");
            hours(placeB, dow, "08:00", "23:00");
            hours(overnightBar, dow, "18:00", "02:00");
        }
        UUID listId = jdbc.sql("INSERT INTO saved_list(owner_id, name) VALUES (:u, 'Yêu thích') RETURNING id")
                .param("u", ownerId).query(UUID.class).single();
        jdbc.sql("INSERT INTO saved_list_item(list_id, place_id) VALUES (:l, :a), (:l, :b)")
                .param("l", listId).param("a", placeA).param("b", placeB).update();
    }

    private UUID place(String code, String name, UUID areaId) {
        return jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, hours_status, status)
                SELECT :code, :name, t.id, :area, 'addr', 10.77, 106.70, 'KNOWN', 'PUBLISHED'
                FROM taxonomy t WHERE t.code = 'CAFE' RETURNING id
                """).param("code", code).param("name", name).param("area", areaId).query(UUID.class).single();
    }

    private void hours(UUID placeId, int dow, String opens, String closes) {
        jdbc.sql("INSERT INTO opening_hours(place_id, day_of_week, opens_at, closes_at) VALUES (:p, :d, CAST(:o AS time), CAST(:c AS time))")
                .param("p", placeId).param("d", dow).param("o", opens).param("c", closes).update();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private UUID createPlan(UUID placeId, OffsetDateTime at) throws Exception {
        return UUID.fromString(body(mvc.perform(post("/api/v1/date-plans")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"%s\",\"scheduledAt\":\"%s\"}".formatted(placeId, at)))
                .andExpect(status().isCreated())).get("id").asText());
    }

    /** Tạo phiên cùng chọn, trả [sessionId, grantId, inviteToken]. */
    private String[] createSession() throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/choice-sessions")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[\"%s\",\"%s\"]}".formatted(placeA, placeB)))
                .andExpect(status().isCreated()));
        return new String[] { created.get("id").asText(), created.get("activeShareGrant").get("id").asText(),
                created.get("shareGrant").get("inviteToken").asText() };
    }

    private org.springframework.test.web.servlet.ResultActions join(String inviteToken, String name) throws Exception {
        return mvc.perform(post("/api/v1/share-grants/{t}/join", inviteToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(name)));
    }

    // ───────────── Hủy kế hoạch ─────────────

    @Test
    void cancelPlanClosesRemindersAndBlocksFurtherChanges() throws Exception {
        UUID planId = createPlan(placeA, OffsetDateTime.now().plusDays(2).withHour(19).withMinute(0));
        jdbc.sql("INSERT INTO date_reminder(plan_id, recipient_user_id, scheduled_at) SELECT id, owner_id, scheduled_at FROM date_plan WHERE id = :p")
                .param("p", planId).update();

        mvc.perform(post("/api/v1/date-plans/{id}/cancel", planId).header("Authorization", strangerToken))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/date-plans/{id}/cancel", planId).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.sql("SELECT status FROM date_reminder WHERE plan_id = :p").param("p", planId)
                .query(String.class).single()).isEqualTo("CANCELLED");

        mvc.perform(post("/api/v1/date-plans/{id}/cancel", planId).header("Authorization", ownerToken))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/date-plans/{id}", planId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"đổi ý\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/date-plans").param("status", "CANCELLED").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(planId.toString()));
    }

    @Test
    void cancelPastPlanIsRejected() throws Exception {
        UUID planId = createPlan(placeA, OffsetDateTime.now().plusDays(1).withHour(19).withMinute(0));
        jdbc.sql("UPDATE date_plan SET scheduled_at = now() - interval '1 hour' WHERE id = :p").param("p", planId).update();
        mvc.perform(post("/api/v1/date-plans/{id}/cancel", planId).header("Authorization", ownerToken))
                .andExpect(status().isConflict());
    }

    // ───────────── Gỡ người nhận, link mời ─────────────

    @Test
    void removeRecipientRevokesGuestAndFreesSlotForNewJoin() throws Exception {
        String[] s = createSession();
        String guest = body(join(s[2], "Linh").andExpect(status().isOk())).get("recipientSession").asText();
        mvc.perform(get("/api/v1/choice-sessions/{id}", s[0]).header("X-Recipient-Session", guest))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/choice-sessions/{id}/recipient", s[0]).header("Authorization", strangerToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/choice-sessions/{id}/recipient", s[0]).header("Authorization", ownerToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/choice-sessions/{id}", s[0]).header("X-Recipient-Session", guest))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/share-grants/{t}", s[2])).andExpect(jsonPath("$.slotAvailable").value(true));
        join(s[2], "Người mới").andExpect(status().isOk());
    }

    @Test
    void expiredOrRevokedLinkIsGone() throws Exception {
        String[] s = createSession();
        jdbc.sql("UPDATE share_grant SET expires_at = now() - interval '1 minute' WHERE id = CAST(:g AS uuid)")
                .param("g", s[1]).update();
        mvc.perform(get("/api/v1/share-grants/{t}", s[2])).andExpect(status().isGone());
        join(s[2], "Linh").andExpect(status().isGone());

        String[] s2 = createSession();
        mvc.perform(delete("/api/v1/choice-sessions/{sid}/share-grants/{gid}", s2[0], s2[1]).header("Authorization", ownerToken))
                .andExpect(status().isNoContent());
        join(s2[2], "Linh").andExpect(status().isGone());
        mvc.perform(get("/api/v1/share-grants/{t}", "a".repeat(43))).andExpect(status().isGone());
        mvc.perform(get("/api/v1/share-grants/{t}", "ngan-qua")).andExpect(status().isBadRequest()); // contract: 32–128 ký tự
    }

    @Test
    void concurrentJoinsLetExactlyOneRecipientIn() throws Exception {
        String[] s = createSession();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = "Khách " + i;
            Callable<Integer> task = () -> {
                start.await();
                return join(s[2], name).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
        assertThat(statuses).filteredOn(code -> code != 200).containsOnly(409);
        assertThat(jdbc.sql("SELECT count(*) FROM recipient_session WHERE session_id = CAST(:s AS uuid) AND revoked_at IS NULL")
                .param("s", s[0]).query(Long.class).single()).isEqualTo(1L);
    }

    // ───────────── Giờ mở cửa khớp bộ lọc tìm kiếm ─────────────

    @Test
    void planAtOvernightTailIsAllowedEvenWhenTodayHasClosedException() throws Exception {
        LocalDate day = LocalDate.now(VN).plusDays(3);
        jdbc.sql("INSERT INTO date_exception(place_id, on_date, closed) VALUES (:p, :d, true)")
                .param("p", overnightBar).param("d", day).update();
        OffsetDateTime oneAm = day.atTime(LocalTime.of(1, 0)).atZone(VN).toOffsetDateTime();
        OffsetDateTime sevenPm = day.atTime(LocalTime.of(19, 0)).atZone(VN).toOffsetDateTime();

        // Tìm kiếm của B coi 01:00 là còn mở (phần qua đêm của hôm trước) và 19:00 là đóng (ngoại lệ hôm nay).
        mvc.perform(get("/api/v1/places").param("at", oneAm.toString()))
                .andExpect(jsonPath("$.items[?(@.name == 'Bar Đêm')]").exists());
        mvc.perform(get("/api/v1/places").param("at", sevenPm.toString()))
                .andExpect(jsonPath("$.items[?(@.name == 'Bar Đêm')]").doesNotExist());

        // Lên kế hoạch ở C phải cho cùng kết luận.
        createPlan(overnightBar, oneAm);
        mvc.perform(post("/api/v1/date-plans")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"%s\",\"scheduledAt\":\"%s\"}".formatted(overnightBar, sevenPm)))
                .andExpect(status().isConflict());
    }
}
