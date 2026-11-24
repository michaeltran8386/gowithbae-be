package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.auth.GoogleIdTokenVerifier;
import vn.gowithbae.auth.OtpDelivery;

/** CR-002 (feedback sau launch): đăng nhập Google, danh sách "Đã lưu", phiên cùng chọn theo danh sách và nhiều người. */
@Testcontainers(disabledWithoutDocker = true)
@Import({TestcontainersConfiguration.class, FeedbackV1ApiTest.Fakes.class})
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=", "app.auth.resend-cooldown=PT0S"
})
class FeedbackV1ApiTest {

    /** Token giả: "ok:<sub>:<email>:<name>" hợp lệ, "unverified:..." email chưa xác minh, còn lại không hợp lệ. */
    static class FakeGoogle implements GoogleIdTokenVerifier {
        @Override
        public GoogleIdentity verify(String idToken) {
            String[] p = idToken.split(":", 4);
            if (p.length == 4 && ("ok".equals(p[0]) || "unverified".equals(p[0]))) {
                return new GoogleIdentity(p[1], p[2], "ok".equals(p[0]), p[3]);
            }
            throw new InvalidGoogleTokenException("bad", null);
        }
    }

    static class Mailbox implements OtpDelivery {
        final Map<String, String> codes = new ConcurrentHashMap<>();
        public void send(String email, String code, String purpose) { codes.put(email, code); }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeGoogle fakeGoogle() { return new FakeGoogle(); }
        @Bean @Primary Mailbox feedbackMailbox() { return new Mailbox(); }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired Mailbox mail;

    UUID ownerId;
    String ownerToken;
    UUID listId;
    UUID placeA;
    UUID placeB;
    UUID placeC;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE request_rate_limit, idempotency_record, audit_log, notification, date_reminder, visit_self_report, date_plan, vote, recipient_session, share_grant, choice_candidate, choice_session, saved_list_item, saved_list, opening_hours, date_exception, place_price, place_tag, place_image, stored_file, place, area, login_challenge, access_session, app_user CASCADE").update();
        mail.codes.clear();
        ownerId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('owner@test.invalid', 'Minh') RETURNING id")
                .query(UUID.class).single();
        ownerToken = "Bearer " + new TestJwtFactory(jdbc, jwtEncoder).token(ownerId, "MEMBER");
        UUID areaId = jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1') RETURNING id").query(UUID.class).single();
        placeA = place("PA", "Quán A", areaId);
        placeB = place("PB", "Quán B", areaId);
        placeC = place("PC", "Quán C", areaId);
        listId = jdbc.sql("INSERT INTO saved_list(owner_id, name) VALUES (:u, 'Danh sách 1') RETURNING id")
                .param("u", ownerId).query(UUID.class).single();
        addToList(placeA);
        addToList(placeB);
    }

    private UUID place(String code, String name, UUID areaId) {
        return jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, hours_status, status)
                SELECT :code, :name, t.id, :area, 'addr', 10.77, 106.70, 'UNKNOWN', 'PUBLISHED'
                FROM taxonomy t WHERE t.code = 'CAFE' RETURNING id
                """).param("code", code).param("name", name).param("area", areaId).query(UUID.class).single();
    }

    private void addToList(UUID placeId) {
        jdbc.sql("INSERT INTO saved_list_item(list_id, place_id) VALUES (:l, :p)").param("l", listId).param("p", placeId).update();
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions google(String idToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("idToken", idToken))));
    }

    // ───────────── Đăng nhập Google ─────────────

    @Test
    void googleLoginCreatesMemberOnceAndOpensSession() throws Exception {
        JsonNode first = body(google("ok:sub-1:Lan@Example.test:Lan Nguyễn").andExpect(status().isOk()));
        assertThat(first.get("user").get("email").asText()).isEqualTo("lan@example.test");
        assertThat(first.get("user").get("displayName").asText()).isEqualTo("Lan Nguyễn");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + first.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("MEMBER"));

        JsonNode again = body(google("ok:sub-1:lan@example.test:Lan").andExpect(status().isOk()));
        assertThat(again.get("user").get("id").asText()).isEqualTo(first.get("user").get("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE google_sub = 'sub-1'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void googleLoginLinksExistingEmailAccount() throws Exception {
        JsonNode token = body(google("ok:sub-owner:OWNER@test.invalid:Minh G").andExpect(status().isOk()));
        assertThat(token.get("user").get("id").asText()).isEqualTo(ownerId.toString());
        assertThat(jdbc.sql("SELECT google_sub FROM app_user WHERE id = :id").param("id", ownerId).query(String.class).single())
                .isEqualTo("sub-owner");
        // Một tài khoản Google khác dùng cùng email không được chiếm tài khoản đã gắn.
        google("ok:sub-intruder:owner@test.invalid:X").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GOOGLE_ACCOUNT_CONFLICT"));
    }

    @Test
    void googleLoginRejectsInvalidUnverifiedAndStaff() throws Exception {
        google("garbage").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GOOGLE_TOKEN_INVALID"));
        google("unverified:sub-2:a@example.test:A").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GOOGLE_EMAIL_UNVERIFIED"));
        UUID staff = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('staff@test.invalid', 'Admin') RETURNING id")
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'SUPER_ADMIN')").param("id", staff).update();
        google("ok:sub-staff:staff@test.invalid:Admin").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_USE_ADMIN_LOGIN"));
        assertThat(jdbc.sql("SELECT count(*) FROM access_session WHERE user_id = :id").param("id", staff).query(Long.class).single())
                .isZero();
    }

    @Test
    void memberEmailOtpIsDisabledButStaffStillGetsCode() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@test.invalid\"}")).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@test.invalid\"}")).andExpect(status().isAccepted());
        assertThat(mail.codes).isEmpty();

        UUID staff = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('staff@test.invalid', 'Admin') RETURNING id")
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'SUPER_ADMIN')").param("id", staff).update();
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"staff@test.invalid\"}")).andExpect(status().isAccepted());
        assertThat(mail.codes).containsKey("staff@test.invalid");
    }

    // ───────────── Danh sách "Đã lưu" ─────────────

    @Test
    void defaultSavedListIsCreatedOnceAndLocked() throws Exception {
        JsonNode lists = body(mvc.perform(get("/api/v1/saved-lists").header("Authorization", ownerToken)).andExpect(status().isOk()));
        assertThat(lists.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(lists.get(0).get("name").asText()).isEqualTo("Đã lưu");
        mvc.perform(get("/api/v1/saved-lists").header("Authorization", ownerToken)).andExpect(jsonPath("$.length()").value(2));
        String defaultId = lists.get(0).get("id").asText();

        mvc.perform(patch("/api/v1/saved-lists/{id}", defaultId).header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Khác\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_LIST_LOCKED"));
        mvc.perform(delete("/api/v1/saved-lists/{id}", defaultId).header("Authorization", ownerToken))
                .andExpect(status().isConflict());
    }

    // ───────────── Phiên cùng chọn theo danh sách, nhiều người ─────────────

    private JsonNode createFromList() throws Exception {
        return body(mvc.perform(post("/api/v1/choice-sessions").header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"listId\":\"%s\"}".formatted(listId)))
                .andExpect(status().isCreated()));
    }

    private String join(String invite, String name) throws Exception {
        return body(mvc.perform(post("/api/v1/share-grants/{t}/join", invite).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", name))))
                .andExpect(status().isOk())).get("recipientSession").asText();
    }

    @Test
    void listSessionFollowsListChangesAndKeepsVotes() throws Exception {
        JsonNode created = createFromList();
        String sid = created.get("id").asText();
        assertThat(created.get("listId").asText()).isEqualTo(listId.toString());
        assertThat(created.get("candidates")).hasSize(2);
        String invite = created.get("shareGrant").get("inviteToken").asText();
        String guest = join(invite, "Linh");

        String candidateA = created.get("candidates").get(0).get("id").asText();
        mvc.perform(put("/api/v1/choice-sessions/{s}/votes/{c}", sid, candidateA).header("X-Recipient-Session", guest)
                .contentType(MediaType.APPLICATION_JSON).content("{\"preference\":\"LIKE\"}")).andExpect(status().isNoContent());

        // Chủ danh sách thêm nơi mới: người được mời thấy ngay, phiếu cũ giữ nguyên.
        addToList(placeC);
        JsonNode guestView = body(mvc.perform(get("/api/v1/choice-sessions/{s}", sid).header("X-Recipient-Session", guest))
                .andExpect(status().isOk()));
        assertThat(guestView.get("candidates")).hasSize(3);
        assertThat(guestView.get("candidates").get(0).get("myVote").asText()).isEqualTo("LIKE");
        assertThat(guestView.get("candidates").get(2).get("place").get("id").asText()).isEqualTo(placeC.toString());
        assertThat(guestView.get("candidates").get(2).path("myVote").isMissingNode() || guestView.get("candidates").get(2).path("myVote").isNull()).isTrue();
        mvc.perform(get("/api/v1/share-grants/{t}", invite)).andExpect(jsonPath("$.candidateCount").value(3));

        // Bỏ nơi khỏi danh sách: ẩn khỏi phiên, không bầu được nữa; thêm lại thì phiếu cũ quay lại.
        jdbc.sql("DELETE FROM saved_list_item WHERE list_id = :l AND place_id = :p").param("l", listId).param("p", placeA).update();
        mvc.perform(get("/api/v1/choice-sessions/{s}", sid).header("X-Recipient-Session", guest))
                .andExpect(jsonPath("$.candidates.length()").value(2));
        mvc.perform(put("/api/v1/choice-sessions/{s}/votes/{c}", sid, candidateA).header("X-Recipient-Session", guest)
                .contentType(MediaType.APPLICATION_JSON).content("{\"preference\":\"SKIP\"}")).andExpect(status().isNotFound());
        addToList(placeA);
        mvc.perform(get("/api/v1/choice-sessions/{s}", sid).header("X-Recipient-Session", guest))
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andExpect(jsonPath("$.candidates[0].myVote").value("LIKE"));

        // Một danh sách chỉ có một phiên chưa chốt; danh sách hiện phiên đó.
        mvc.perform(post("/api/v1/choice-sessions").header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"listId\":\"%s\"}".formatted(listId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIST_HAS_ACTIVE_SESSION"));
        mvc.perform(get("/api/v1/saved-lists/{id}", listId).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.activeChoiceSessionId").value(sid));
    }

    @Test
    void unlimitedParticipantsCountLikesForCreatorOnly() throws Exception {
        JsonNode created = createFromList();
        String sid = created.get("id").asText();
        String invite = created.get("shareGrant").get("inviteToken").asText();
        String candidateA = created.get("candidates").get(0).get("id").asText();

        List<String> guests = new ArrayList<>();
        for (String name : List.of("Linh", "Huy", "Trang", "Phúc")) guests.add(join(invite, name));
        for (String g : guests) {
            mvc.perform(put("/api/v1/choice-sessions/{s}/votes/{c}", sid, candidateA).header("X-Recipient-Session", g)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"preference\":\"LIKE\"}")).andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/v1/choice-sessions/{s}", sid).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.participantNames.length()").value(4))
                .andExpect(jsonPath("$.recipientName").value("Linh, Huy, Trang, Phúc"))
                .andExpect(jsonPath("$.candidates[0].likeCount").value(4))
                .andExpect(jsonPath("$.candidates[0].mutualLike").value(false));
        mvc.perform(put("/api/v1/choice-sessions/{s}/votes/{c}", sid, candidateA).header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"preference\":\"LIKE\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/choice-sessions/{s}", sid).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.candidates[0].likeCount").value(5))
                .andExpect(jsonPath("$.candidates[0].mutualLike").value(true));
        // Người được mời không thấy số phiếu của người khác trước khi chốt (OQ-07).
        mvc.perform(get("/api/v1/choice-sessions/{s}", sid).header("X-Recipient-Session", guests.get(0)))
                .andExpect(jsonPath("$.candidates[0].likeCount").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].myVote").value("LIKE"));
    }
}
