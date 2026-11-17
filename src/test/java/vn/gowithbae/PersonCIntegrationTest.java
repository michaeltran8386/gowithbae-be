package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Kiểm thử tích hợp Postgres cho luồng Người 3 (V1..V4 + V300). Chạy khi có Docker/Testcontainers. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
class PersonCIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;

    UUID ownerId;
    UUID reviewerId;
    UUID supportId;
    UUID openPlaceId;
    UUID unknownHoursPlaceId;
    UUID hiddenPlaceId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE idempotency_record, audit_log, notification, support_message_file, support_message, support_ticket, place_report, date_reminder, visit_self_report, date_plan, vote, recipient_session, share_grant, choice_candidate, choice_session, saved_list_item, saved_list, opening_hours, date_exception, place_price, place_tag, place_image, stored_file, place, area, app_user CASCADE").update();

        ownerId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('owner@test.invalid', 'Minh') RETURNING id")
                .query(UUID.class).single();
        reviewerId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('reviewer@test.invalid', 'Lan') RETURNING id")
                .query(UUID.class).single();
        supportId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('support@test.invalid', 'Hung') RETURNING id")
                .query(UUID.class).single();

        UUID areaId = jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1') RETURNING id")
                .query(UUID.class).single();

        openPlaceId = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, hours_status, status)
                SELECT 'OPEN1', 'Bistro Ánh Nến', t.id, :area, '10 Lê Lợi', 10.77, 106.70, 'KNOWN', 'PUBLISHED'
                FROM taxonomy t WHERE t.code = 'CAFE' RETURNING id
                """).param("area", areaId).query(UUID.class).single();
        for (int dow = 1; dow <= 7; dow++) {
            jdbc.sql("INSERT INTO opening_hours(place_id, day_of_week, opens_at, closes_at) VALUES (:pid, :dow, '08:00', '23:00')")
                    .param("pid", openPlaceId).param("dow", dow).update();
        }

        unknownHoursPlaceId = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, hours_status, status)
                SELECT 'UNK1', 'Gốm Bình Thạnh', t.id, :area, '22 Nguyễn Gia Trí', 10.80, 106.71, 'UNKNOWN', 'PUBLISHED'
                FROM taxonomy t WHERE t.code = 'CAFE' RETURNING id
                """).param("area", areaId).query(UUID.class).single();

        hiddenPlaceId = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, hours_status, status)
                SELECT 'HID1', 'Quán Tạm Ẩn', t.id, :area, '99 Pasteur', 10.78, 106.69, 'UNKNOWN', 'HIDDEN'
                FROM taxonomy t WHERE t.code = 'CAFE' RETURNING id
                """).param("area", areaId).query(UUID.class).single();

        UUID listId = jdbc.sql("INSERT INTO saved_list(owner_id, name) VALUES (:uid, 'Yêu thích') RETURNING id")
                .param("uid", ownerId).query(UUID.class).single();
        jdbc.sql("INSERT INTO saved_list_item(list_id, place_id) VALUES (:lid, :p1), (:lid, :p2)")
                .param("lid", listId).param("p1", openPlaceId).param("p2", unknownHoursPlaceId).update();
    }

    @Test
    void choiceSessionVoteHideFinalizeAndRevokeEndToEnd() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        String createBody = """
                {"placeIds":["%s","%s"]}
                """.formatted(openPlaceId, unknownHoursPlaceId);

        MvcResult createRes = mvc.perform(post("/api/v1/choice-sessions")
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.shareGrant.inviteToken").isNotEmpty())
                .andReturn();

        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        UUID sessionId = UUID.fromString(created.get("id").asText());
        UUID grantId = UUID.fromString(created.get("activeShareGrant").get("id").asText());
        String inviteToken = created.get("shareGrant").get("inviteToken").asText();
        UUID candidateOpenId = UUID.fromString(created.get("candidates").get(0).get("id").asText());

        // Retry same Idempotency-Key returns same session id
        mvc.perform(post("/api/v1/choice-sessions")
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sessionId.toString()));

        // Guest preview without login
        mvc.perform(get("/api/v1/share-grants/{token}", inviteToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotAvailable").value(true))
                .andExpect(jsonPath("$.candidateCount").value(2));

        // Guest join + second join blocked (409)
        MvcResult joinRes = mvc.perform(post("/api/v1/share-grants/{token}/join", inviteToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Linh\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String guestSession = objectMapper.readTree(joinRes.getResponse().getContentAsString())
                .get("recipientSession").asText();

        mvc.perform(post("/api/v1/share-grants/{token}/join", inviteToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Người thứ 2\"}"))
                .andExpect(status().isConflict());

        // Both cast LIKE -> partnerVote remains hidden before finalize (OQ-07), mutualLike is true
        mvc.perform(put("/api/v1/choice-sessions/{sid}/votes/{cid}", sessionId, candidateOpenId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"LIKE\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(put("/api/v1/choice-sessions/{sid}/votes/{cid}", sessionId, candidateOpenId)
                        .header("X-Recipient-Session", guestSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"LIKE\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/choice-sessions/{sid}", sessionId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].mutualLike").value(true))
                .andExpect(jsonPath("$.candidates[0].partnerVote").doesNotExist());

        // Finalize session -> creates DatePlan
        OffsetDateTime futureOpen = OffsetDateTime.now().plusDays(2).withHour(19).withMinute(0).withSecond(0).withNano(0);
        MvcResult finalizeRes = mvc.perform(post("/api/v1/choice-sessions/{sid}/finalize", sessionId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateId":"%s","scheduledAt":"%s","note":"Chốt quán này nhé"}
                                """.formatted(candidateOpenId, futureOpen)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.choiceSessionId").value(sessionId.toString()))
                .andReturn();
        UUID planId = UUID.fromString(objectMapper.readTree(finalizeRes.getResponse().getContentAsString()).get("id").asText());

        // After finalize, partnerVote becomes visible
        mvc.perform(get("/api/v1/choice-sessions/{sid}", sessionId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"))
                .andExpect(jsonPath("$.candidates[0].partnerVote").value("LIKE"));

        // Guest can view DatePlan while grant is valid (even if grant expires later)
        jdbc.sql("UPDATE share_grant SET expires_at = now() - interval '1 hour' WHERE id = :gid")
                .param("gid", grantId).update();
        mvc.perform(get("/api/v1/date-plans/{pid}", planId)
                        .header("X-Recipient-Session", guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("RECIPIENT"));

        // Revoking grant blocks BOTH ChoiceSession and DatePlan immediately (OQ-09)
        mvc.perform(delete("/api/v1/choice-sessions/{sid}/share-grants/{gid}", sessionId, grantId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/choice-sessions/{sid}", sessionId)
                        .header("X-Recipient-Session", guestSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/date-plans/{pid}", planId)
                        .header("X-Recipient-Session", guestSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void reminderDismissRescheduleConfirmAndVisitCountWorkEndToEnd() throws Exception {
        // Direct plan on unknown hours -> 201 with HOURS_UNKNOWN warning
        OffsetDateTime future = OffsetDateTime.now().plusDays(1);
        MvcResult planRes = mvc.perform(post("/api/v1/date-plans")
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":"%s","scheduledAt":"%s"}
                                """.formatted(unknownHoursPlaceId, future)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0].code").value("HOURS_UNKNOWN"))
                .andReturn();
        UUID planId = UUID.fromString(objectMapper.readTree(planRes.getResponse().getContentAsString()).get("id").asText());

        // Hidden place blocked (409)
        mvc.perform(post("/api/v1/date-plans")
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":"%s","scheduledAt":"%s"}
                                """.formatted(hiddenPlaceId, future)))
                .andExpect(status().isConflict());

        // Move plan into the past in DB to test due reminder -> DISMISS -> reschedule -> CONFIRM_VISIT
        jdbc.sql("UPDATE date_plan SET scheduled_at = now() - interval '1 hour' WHERE id = :pid")
                .param("pid", planId).update();

        mvc.perform(get("/api/v1/date-plans/{pid}/reminder", planId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shouldShow").value(true))
                .andExpect(jsonPath("$.reminder").doesNotExist());

        mvc.perform(put("/api/v1/date-plans/{pid}/reminder", planId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminder.status").value("DISMISSED"))
                .andExpect(jsonPath("$.shouldShow").value(false));

        // Visit count remains 0 after DISMISS
        mvc.perform(get("/api/v1/me/places/{pid}/visit-count", unknownHoursPlaceId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // Reset scheduled_at to future temporarily so updateDatePlan is allowed (not PAST per OQ-10)
        jdbc.sql("UPDATE date_plan SET scheduled_at = now() + interval '1 day' WHERE id = :pid")
                .param("pid", planId).update();
        OffsetDateTime newFuture = OffsetDateTime.now().plusDays(3);
        mvc.perform(patch("/api/v1/date-plans/{pid}", planId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scheduledAt":"%s"}
                                """.formatted(newFuture)))
                .andExpect(status().isOk());

        // Reschedule resets DISMISSED reminder -> PENDING; when time arrives, owner can CONFIRM_VISIT
        jdbc.sql("UPDATE date_plan SET scheduled_at = now() - interval '10 minutes' WHERE id = :pid")
                .param("pid", planId).update();
        mvc.perform(put("/api/v1/date-plans/{pid}/reminder", planId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM_VISIT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminder.status").value("ANSWERED"))
                .andExpect(jsonPath("$.shouldShow").value(false));

        // Visit count is now 1, and changing to false returns 409 (locked per A4)
        mvc.perform(get("/api/v1/me/places/{pid}/visit-count", unknownHoursPlaceId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mvc.perform(put("/api/v1/date-plans/{pid}/visit-report", planId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"false\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void placeReportRateLimitAndSupportInternalIsolationWork() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/places/{pid}/reports", openPlaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"WRONG_PRICE\",\"description\":\"Giá thực tế cao hơn\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/places/{pid}/reports", openPlaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WRONG_PRICE\"}"))
                .andExpect(status().isTooManyRequests());

        // Support ticket creation + INTERNAL note isolation + CLOSED lock (OQ-13)
        MvcResult ticketRes = mvc.perform(post("/api/v1/support-tickets")
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DATE_PLAN\",\"subject\":\"Không tải được lịch\",\"body\":\"Giúp mình với\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID ticketId = UUID.fromString(objectMapper.readTree(ticketRes.getResponse().getContentAsString()).get("id").asText());

        mvc.perform(post("/api/v1/admin/support-tickets/{tid}/messages", ticketId)
                        .with(jwt().jwt(j -> j.subject(supportId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Ghi chú nội bộ\",\"visibility\":\"INTERNAL\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/support-tickets/{tid}", ticketId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1));

        mvc.perform(post("/api/v1/admin/support-tickets/{tid}/transitions", ticketId)
                        .with(jwt().jwt(j -> j.subject(supportId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(post("/api/v1/support-tickets/{tid}/messages", ticketId)
                        .with(jwt().jwt(j -> j.subject(ownerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Mở lại giúp mình\"}"))
                .andExpect(status().isConflict());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE object_id = :oid")
                .param("oid", ticketId.toString()).query(Long.class).single()).isEqualTo(1L);
    }
}
