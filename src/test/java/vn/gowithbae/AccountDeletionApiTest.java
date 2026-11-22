package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.gowithbae.adminaccount.AccountDeletionJob;

/** Khôi phục và xóa tài khoản (ẩn danh hóa), tự xóa sau thời hạn. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=",
        "app.accounts.deletion-grace-days=30", "app.accounts.deletion-check-initial-ms=3600000"})
class AccountDeletionApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired AccountDeletionJob job;

    UUID admin;
    UUID member;
    String adminToken;
    UUID placeId;
    UUID reportId;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                TRUNCATE notification, support_message_file, support_message, support_ticket, place_report, date_plan_invite,
                         date_reminder, visit_self_report, date_plan, vote, recipient_session, share_grant, choice_candidate,
                         choice_session, saved_list_item, saved_list, analytics_event, staff_mfa, place, area, stored_file,
                         access_session, login_challenge, app_user CASCADE
                """).update();
        admin = user("owner@test.invalid", "Owner");
        member = user("member@test.invalid", "Minh");
        var tokens = new TestJwtFactory(jdbc, encoder);
        adminToken = "Bearer " + tokens.token(admin, "SUPER_ADMIN");
        tokens.token(member, "MEMBER"); // phiên đăng nhập của member

        UUID area = jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1') RETURNING id").query(UUID.class).single();
        placeId = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, status)
                SELECT 'P1', 'Quán A', (SELECT id FROM taxonomy WHERE code = 'CAFE' LIMIT 1), :area, 'addr', 10.7, 106.7, 'PUBLISHED' RETURNING id
                """).param("area", area).query(UUID.class).single();
        UUID place2 = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, status)
                SELECT 'P2', 'Quán B', (SELECT id FROM taxonomy WHERE code = 'CAFE' LIMIT 1), :area, 'addr', 10.7, 106.7, 'PUBLISHED' RETURNING id
                """).param("area", area).query(UUID.class).single();

        // Dữ liệu cá nhân của member ở mọi module.
        UUID list = jdbc.sql("INSERT INTO saved_list(owner_id, name) VALUES (:u, 'Yêu thích') RETURNING id").param("u", member).query(UUID.class).single();
        jdbc.sql("INSERT INTO saved_list_item(list_id, place_id) VALUES (:l, :p)").param("l", list).param("p", placeId).update();
        UUID session = jdbc.sql("INSERT INTO choice_session(creator_id, status) VALUES (:u, 'FINALIZED') RETURNING id").param("u", member).query(UUID.class).single();
        UUID cand = jdbc.sql("INSERT INTO choice_candidate(session_id, place_id) VALUES (:s, :p) RETURNING id").param("s", session).param("p", placeId).query(UUID.class).single();
        jdbc.sql("INSERT INTO choice_candidate(session_id, place_id, sort_order) VALUES (:s, :p, 1)").param("s", session).param("p", place2).update();
        UUID grant = jdbc.sql("INSERT INTO share_grant(session_id, token_hash, expires_at) VALUES (:s, 'h1', now() + interval '1 day') RETURNING id")
                .param("s", session).query(UUID.class).single();
        UUID recipient = jdbc.sql("INSERT INTO recipient_session(share_grant_id, session_id, display_name, token_hash) VALUES (:g, :s, 'Hà', 'r1') RETURNING id")
                .param("g", grant).param("s", session).query(UUID.class).single();
        jdbc.sql("INSERT INTO vote(candidate_id, voter_user_id, preference) VALUES (:c, :u, 'LIKE')").param("c", cand).param("u", member).update();
        jdbc.sql("INSERT INTO vote(candidate_id, voter_recipient_id, preference) VALUES (:c, :r, 'LIKE')").param("c", cand).param("r", recipient).update();
        UUID plan = jdbc.sql("""
                INSERT INTO date_plan(owner_id, place_id, scheduled_at, note, status, choice_session_id, recipient_session_id)
                VALUES (:u, :p, now() + interval '2 days', 'riêng tư', 'SCHEDULED', :s, :r) RETURNING id
                """).param("u", member).param("p", placeId).param("s", session).param("r", recipient).query(UUID.class).single();
        jdbc.sql("INSERT INTO date_plan_invite(plan_id, token_hash) VALUES (:p, 'inv1')").param("p", plan).update();
        jdbc.sql("INSERT INTO date_plan(owner_id, place_id, scheduled_at, status) VALUES (:u, :p, now() + interval '3 days', 'SCHEDULED')")
                .param("u", member).param("p", place2).update();
        UUID ticket = jdbc.sql("INSERT INTO support_ticket(owner_id, category, subject) VALUES (:u, 'OTHER', 'Hỏi') RETURNING id")
                .param("u", member).query(UUID.class).single();
        jdbc.sql("INSERT INTO support_message(ticket_id, author_id, visibility, body) VALUES (:t, :u, 'PUBLIC', 'nội dung riêng')")
                .param("t", ticket).param("u", member).update();
        reportId = jdbc.sql("INSERT INTO place_report(place_id, reason, description, sender_user_id) VALUES (:p, 'WRONG_PRICE', 'giá sai', :u) RETURNING id")
                .param("p", placeId).param("u", member).query(UUID.class).single();
        jdbc.sql("INSERT INTO notification(recipient_id, type, summary) VALUES (:u, 'ADMIN_ANNOUNCEMENT', 'xin chào')").param("u", member).update();
        jdbc.sql("""
                INSERT INTO analytics_event(event_id, name, occurred_at, session_id, platform, user_id)
                VALUES (gen_random_uuid(), 'place_viewed', now(), 's1', 'WEB_DESKTOP', :u)
                """).param("u", member).update();
    }

    private UUID user(String email, String name) {
        return jdbc.sql("INSERT INTO app_user(email, display_name) VALUES (:e, :n) RETURNING id").param("e", email).param("n", name)
                .query(UUID.class).single();
    }

    private void requestDeletion(UUID id, String interval) {
        jdbc.sql("UPDATE app_user SET status = 'DELETION_REQUESTED', deletion_requested_at = now() - CAST(:i AS interval) WHERE id = :id")
                .param("i", interval).param("id", id).update();
    }

    private ResultActions erase(UUID id, String reason, String confirmEmail) throws Exception {
        return mvc.perform(post("/api/v1/admin/users/{id}/erase", id).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"%s\",\"confirmEmail\":\"%s\"}".formatted(reason, confirmEmail)));
    }

    private long count(String sql) {
        return jdbc.sql(sql).param("u", member).query(Long.class).single();
    }

    @Test
    void restoreReturnsRequestedAccountToActiveAndShowsSchedule() throws Exception {
        requestDeletion(member, "2 days");
        mvc.perform(get("/api/v1/admin/users/{id}", member).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETION_REQUESTED"))
                .andExpect(jsonPath("$.scheduledDeletionAt").isNotEmpty());
        // Đang chờ xóa: khóa/đổi vai trò báo rõ bằng tiếng Việt.
        mvc.perform(post("/api/v1/admin/users/{id}/lock", member).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("đang chờ xóa")));

        mvc.perform(post("/api/v1/admin/users/{id}/restore", member).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Người dùng nhờ giữ lại\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.deletionRequestedAt").isEmpty());
        assertThat(count("SELECT count(*) FROM saved_list WHERE owner_id = :u")).isEqualTo(1L);

        mvc.perform(post("/api/v1/admin/users/{id}/restore", member).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"lại\"}")).andExpect(status().isConflict());
    }

    @Test
    void eraseRemovesPersonalDataButKeepsHistoryAndFreesTheEmail() throws Exception {
        erase(member, "Tài khoản rác", "MEMBER@test.invalid ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.displayName").value("Tài khoản đã xóa"))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());

        assertThat(jdbc.sql("SELECT email FROM app_user WHERE id = :u").param("u", member).query(String.class).single())
                .isEqualTo("deleted+" + member + "@deleted.invalid");
        assertThat(count("SELECT count(*) FROM saved_list WHERE owner_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM date_plan WHERE owner_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM choice_session WHERE creator_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM vote WHERE voter_user_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM support_ticket WHERE owner_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE recipient_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM access_session WHERE user_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM user_role WHERE user_id = :u")).isZero();
        assertThat(count("SELECT count(*) FROM analytics_event WHERE user_id = :u")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_event WHERE anonymous_id LIKE 'erased-%'").query(Long.class).single()).isEqualTo(1L);

        // Báo sai vẫn còn, chỉ bỏ người gửi; nhật ký ghi lý do.
        assertThat(jdbc.sql("SELECT sender_user_id FROM place_report WHERE id = :r").param("r", reportId).query(UUID.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT reason FROM audit_log WHERE action = 'USER_ERASED' AND object_id = :u").param("u", member.toString())
                .query(String.class).single()).isEqualTo("Tài khoản rác");

        // Email cũ đăng ký lại được (tài khoản mới).
        user("member@test.invalid", "Minh mới");

        // Đã xóa: không xóa lại, không khôi phục.
        erase(member, "lại", "deleted+" + member + "@deleted.invalid").andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/users/{id}/restore", member).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isConflict());
    }

    @Test
    void eraseRequiresMatchingEmailAndReasonBlocksSelfAndAllowsErasingAnotherAdmin() throws Exception {
        erase(member, "rác", "khac@test.invalid").andExpect(status().isBadRequest());
        erase(member, " ", "member@test.invalid").andExpect(status().isBadRequest());
        erase(admin, "tự xóa", "owner@test.invalid").andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM saved_list WHERE owner_id = :u")).isEqualTo(1L);

        // Xóa được admin khác: người bấm xóa là admin đang hoạt động nên luôn còn ít nhất một admin.
        UUID other = user("other-admin@test.invalid", "Admin 2");
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:u, 'SUPER_ADMIN')").param("u", other).update();
        jdbc.sql("UPDATE app_user SET status = 'LOCKED' WHERE id = :u").param("u", admin).update();
        String otherToken = "Bearer " + new TestJwtFactory(jdbc, encoder).token(other, "SUPER_ADMIN");
        jdbc.sql("UPDATE app_user SET status = 'ACTIVE' WHERE id = :u").param("u", admin).update();
        mvc.perform(post("/api/v1/admin/users/{id}/erase", admin).header("Authorization", otherToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"confirmEmail\":\"owner@test.invalid\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void jobErasesOnlyAccountsPastTheGracePeriod() {
        UUID recent = user("recent@test.invalid", "Mới yêu cầu");
        requestDeletion(recent, "5 days");
        requestDeletion(member, "31 days");

        assertThat(job.purgeDue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM app_user WHERE id = :u").param("u", member).query(String.class).single()).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT status FROM app_user WHERE id = :u").param("u", recent).query(String.class).single()).isEqualTo("DELETION_REQUESTED");
        assertThat(jdbc.sql("SELECT actor_id FROM audit_log WHERE action = 'USER_ERASED' AND object_id = :u").param("u", member.toString())
                .query(UUID.class).optional()).isEmpty(); // hệ thống thực hiện
        assertThat(job.purgeDue()).isZero();
    }
}
