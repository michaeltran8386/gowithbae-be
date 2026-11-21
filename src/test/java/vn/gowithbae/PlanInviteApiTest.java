package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

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

import tools.jackson.databind.ObjectMapper;

/** Mời người ấy xem kế hoạch tạo tay qua link và trả lời có đi hay không. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=",
        "DB_PASSWORD=", "app.web.base-url=http://web.test"})
class PlanInviteApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;

    UUID ownerId;
    String ownerToken;
    String otherToken;
    UUID placeId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE notification, date_plan_invite, date_plan, place, area, access_session, app_user CASCADE").update();
        ownerId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('owner@test.invalid', 'Minh') RETURNING id")
                .query(UUID.class).single();
        UUID other = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('other@test.invalid', 'Lan') RETURNING id")
                .query(UUID.class).single();
        var tokens = new TestJwtFactory(jdbc, encoder);
        ownerToken = "Bearer " + tokens.token(ownerId, "MEMBER");
        otherToken = "Bearer " + tokens.token(other, "MEMBER");
        UUID area = jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1') RETURNING id").query(UUID.class).single();
        placeId = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, hours_status, status)
                SELECT 'OPEN1', 'Bistro Ánh Nến', t.id, :area, '10 Lê Lợi', 10.77, 106.70, 'UNKNOWN', 'PUBLISHED'
                FROM taxonomy t WHERE t.code = 'CAFE' LIMIT 1 RETURNING id
                """).param("area", area).query(UUID.class).single();
    }

    private UUID plan(OffsetDateTime at, String status) {
        return jdbc.sql("""
                INSERT INTO date_plan(owner_id, place_id, scheduled_at, note, status)
                VALUES (:owner, :place, :at, 'Ghi chú riêng: mua hoa', :status) RETURNING id
                """).param("owner", ownerId).param("place", placeId).param("at", at).param("status", status)
                .query(UUID.class).single();
    }

    private String createInvite(UUID planId) throws Exception {
        String body = mvc.perform(post("/api/v1/date-plans/{id}/invite", planId).header("Authorization", ownerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviteUrl", startsWith("http://web.test/plan-invite/")))
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(body).get("inviteUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private org.springframework.test.web.servlet.ResultActions respond(String token, String body) throws Exception {
        return mvc.perform(put("/api/v1/plan-invites/{t}/response", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void guestViewsWithoutNoteRespondsAndOwnerIsNotified() throws Exception {
        UUID planId = plan(OffsetDateTime.now().plusDays(2), "SCHEDULED");
        String token = createInvite(planId);

        // Chỉ lưu hash của token.
        assertThat(jdbc.sql("SELECT count(*) FROM date_plan_invite WHERE token_hash = :t").param("t", token)
                .query(Long.class).single()).isZero();

        mvc.perform(get("/api/v1/plan-invites/{t}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Minh"))
                .andExpect(jsonPath("$.place.name").value("Bistro Ánh Nến"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.response").isEmpty())
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.warnings[0].code").value("HOURS_UNKNOWN"));

        respond(token, "{\"response\":\"GOING\",\"guestName\":\"  Hà  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("GOING"))
                .andExpect(jsonPath("$.guestName").value("Hà"));

        assertThat(jdbc.sql("SELECT summary FROM notification WHERE recipient_id = :o AND type = 'DATE_PLAN_INVITE_RESPONDED'")
                .param("o", ownerId).query(String.class).single()).startsWith("Hà sẽ đi: Bistro Ánh Nến lúc ");

        // Trả lời lại không cần nhập tên: giữ tên cũ.
        respond(token, "{\"response\":\"NOT_GOING\"}").andExpect(status().isOk()).andExpect(jsonPath("$.guestName").value("Hà"));

        mvc.perform(get("/api/v1/date-plans/{id}/invite", planId).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("NOT_GOING"))
                .andExpect(jsonPath("$.guestName").value("Hà"));

        String ics = mvc.perform(get("/api/v1/plan-invites/{t}/calendar.ics", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ics).contains("BEGIN:VEVENT").doesNotContain("mua hoa");
    }

    @Test
    void recreatingOrRevokingInvalidatesTheOldLink() throws Exception {
        UUID planId = plan(OffsetDateTime.now().plusDays(2), "SCHEDULED");
        String first = createInvite(planId);
        respond(first, "{\"response\":\"GOING\"}").andExpect(status().isOk());

        String second = createInvite(planId);
        mvc.perform(get("/api/v1/plan-invites/{t}", first)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/date-plans/{id}/invite", planId).header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response").isEmpty());

        mvc.perform(delete("/api/v1/date-plans/{id}/invite", planId).header("Authorization", ownerToken)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/plan-invites/{t}", second)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/date-plans/{id}/invite", planId).header("Authorization", ownerToken)).andExpect(status().isNotFound());
    }

    @Test
    void onlyOwnerOfUpcomingPlanCanInviteAndCancelledOrExpiredPlansAreHandled() throws Exception {
        UUID planId = plan(OffsetDateTime.now().plusDays(2), "SCHEDULED");
        mvc.perform(post("/api/v1/date-plans/{id}/invite", planId).header("Authorization", otherToken)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/date-plans/{id}/invite", planId)).andExpect(status().isUnauthorized());

        UUID cancelled = plan(OffsetDateTime.now().plusDays(2), "CANCELLED");
        mvc.perform(post("/api/v1/date-plans/{id}/invite", cancelled).header("Authorization", ownerToken)).andExpect(status().isConflict());

        // Hủy sau khi đã mời: người nhận vẫn xem được trạng thái "đã hủy" nhưng không trả lời được.
        String token = createInvite(planId);
        jdbc.sql("UPDATE date_plan SET status = 'CANCELLED' WHERE id = :id").param("id", planId).update();
        mvc.perform(get("/api/v1/plan-invites/{t}", token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        respond(token, "{\"response\":\"GOING\"}").andExpect(status().isConflict());

        // Hết hạn 1 ngày sau giờ hẹn.
        UUID past = plan(OffsetDateTime.now().plusDays(2), "SCHEDULED");
        String pastToken = createInvite(past);
        jdbc.sql("UPDATE date_plan SET scheduled_at = now() - interval '25 hours' WHERE id = :id").param("id", past).update();
        mvc.perform(get("/api/v1/plan-invites/{t}", pastToken)).andExpect(status().isGone());

        // Chủ bị khóa: link mất hiệu lực.
        UUID another = plan(OffsetDateTime.now().plusDays(3), "SCHEDULED");
        String lockedToken = createInvite(another);
        jdbc.sql("UPDATE app_user SET status = 'LOCKED' WHERE id = :id").param("id", ownerId).update();
        mvc.perform(get("/api/v1/plan-invites/{t}", lockedToken)).andExpect(status().isGone());

        mvc.perform(get("/api/v1/plan-invites/{t}", "x".repeat(43))).andExpect(status().isGone());
    }

    @Test
    void responseIsValidated() throws Exception {
        String token = createInvite(plan(OffsetDateTime.now().plusDays(2), "SCHEDULED"));
        respond(token, "{\"response\":\"MAYBE\"}").andExpect(status().isBadRequest());
        respond(token, "{\"response\":\"GOING\",\"guestName\":\"" + "a".repeat(51) + "\"}").andExpect(status().isBadRequest());
    }
}
