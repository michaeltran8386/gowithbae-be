package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import vn.gowithbae.auth.MfaActivationService;
import vn.gowithbae.auth.OtpDelivery;
import vn.gowithbae.auth.StaffMfa;

/** Trang quản trị: đăng nhập admin + kích hoạt TOTP, tạo tài khoản, phiên đăng nhập, hoạt động. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import({TestcontainersConfiguration.class, AdminConsoleApiTest.MailboxConfiguration.class})
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=",
        "DB_PASSWORD=", "app.auth.resend-cooldown=PT0S", "app.web.base-url=http://web.test"})
@AutoConfigureMockMvc
class AdminConsoleApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired Mailbox mail;
    @Autowired MfaActivationService activation;

    static class Mailbox implements OtpDelivery {
        final Map<String, String> codes = new ConcurrentHashMap<>();
        public void send(String email, String code, String purpose) { codes.put(email, code); }
    }

    @TestConfiguration
    static class MailboxConfiguration {
        @Bean @Primary Mailbox mailbox() { return new Mailbox(); }
    }

    UUID admin;
    String adminToken;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE mfa_activation, staff_mfa, audit_log, analytics_event, saved_list_item, saved_list, app_user, login_challenge, request_rate_limit CASCADE").update();
        mail.codes.clear();
        admin = user("owner@example.test");
        adminToken = "Bearer " + new TestJwtFactory(jdbc, encoder).token(admin, "SUPER_ADMIN");
    }

    private UUID user(String email) {
        return jdbc.sql("INSERT INTO app_user(email, display_name) VALUES (:e, :e) RETURNING id").param("e", email)
                .query(UUID.class).single();
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions requestAndVerify(String email, String staffCode) throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email)))).andExpect(status().isAccepted());
        return verify(email, mail.codes.get(email), staffCode);
    }

    private ResultActions verify(String email, String code, String staffCode) throws Exception {
        var payload = new java.util.HashMap<String, String>();
        payload.put("email", email);
        payload.put("code", code);
        if (staffCode != null) payload.put("staffCode", staffCode);
        return mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Browser/1.0").header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .content(json.writeValueAsString(payload)));
    }

    private static String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    // ───────────── Đăng nhập admin + kích hoạt TOTP ─────────────

    @Test
    void adminActivatesTotpThroughOneTimeLinkThenLogsInWithTwoSteps() throws Exception {
        UUID owner = user("boss@example.test");
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'SUPER_ADMIN')").param("id", owner).update();

        // Chưa kích hoạt TOTP: báo rõ, không cấp phiên.
        requestAndVerify("boss@example.test", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));

        String url = activation.issue(owner, admin).getActivationUrl();
        assertThat(url).startsWith("http://web.test/admin/activate/");
        String token = tokenOf(url);

        JsonNode preview = body(mvc.perform(get("/api/v1/auth/mfa-activation/{t}", token)).andExpect(status().isOk()));
        assertThat(preview.get("email").asString()).isEqualTo("boss@example.test");
        assertThat(preview.get("otpauthUri").asString()).startsWith("otpauth://totp/GoWithBae").contains("secret=");
        String secret = preview.get("secret").asString();

        mvc.perform(post("/api/v1/auth/mfa-activation/{t}", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}")).andExpect(status().isBadRequest());
        long step = Instant.now().getEpochSecond() / 30;
        mvc.perform(post("/api/v1/auth/mfa-activation/{t}", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + StaffMfa.code(secret, step) + "\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/mfa-activation/{t}", token)).andExpect(status().isGone());

        // Đã kích hoạt: thiếu mã → 401 STAFF_CODE_REQUIRED, OTP email vẫn dùng được cho lần gửi kèm mã.
        requestAndVerify("boss@example.test", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("STAFF_CODE_REQUIRED"));
        String otp = mail.codes.get("boss@example.test");
        // Mã đã dùng để kích hoạt không dùng lại được; mã bước kế tiếp hợp lệ.
        verify("boss@example.test", otp, StaffMfa.code(secret, step)).andExpect(status().isBadRequest());
        requestAndVerify("boss@example.test", StaffMfa.code(secret, step + 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        var session = jdbc.sql("SELECT ip_address, user_agent, last_seen_at IS NOT NULL AS seen FROM access_session WHERE user_id = :id")
                .param("id", owner).query((rs, i) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3) }).single();
        assertThat(session[0]).isEqualTo("203.0.113.7");
        assertThat(session[1]).isEqualTo("JUnit-Browser/1.0");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'MFA_ENROLLED' AND object_id = :id")
                .param("id", owner.toString()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void activationLinkBurnsAfterTooManyWrongCodesAndMembersCannotGetOne() throws Exception {
        UUID owner = user("boss2@example.test");
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'SUPER_ADMIN')").param("id", owner).update();
        String token = tokenOf(activation.issue(owner, admin).getActivationUrl());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/mfa-activation/{t}", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"000000\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/auth/mfa-activation/{t}", token)).andExpect(status().isGone());

        UUID member = user("member@example.test");
        mvc.perform(post("/api/v1/admin/users/{id}/mfa-activation", member).header("Authorization", adminToken))
                .andExpect(status().isConflict());
    }

    // ───────────── Tạo tài khoản ─────────────

    @Test
    void superAdminCreatesMemberAndAdminAccounts() throws Exception {
        mvc.perform(post("/api/v1/admin/users").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"New.Member@Example.test\",\"displayName\":\"Linh\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("new.member@example.test"))
                .andExpect(jsonPath("$.user.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.activation").doesNotExist());
        mvc.perform(post("/api/v1/admin/users").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.member@example.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        mvc.perform(post("/api/v1/admin/users").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"second.admin@example.test\",\"roles\":[\"SUPER_ADMIN\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.roles[0]").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.activation.activationUrl", startsWith("http://web.test/admin/activate/")));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'USER_CREATED'").query(Long.class).single())
                .isEqualTo(2L);

        UUID member = user("plain@example.test");
        String memberToken = "Bearer " + new TestJwtFactory(jdbc, encoder).token(member, "MEMBER");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.test\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());
    }

    // ───────────── Chi tiết, phiên, hoạt động ─────────────

    @Test
    void detailSessionsActivityAndRevokeAll() throws Exception {
        UUID member = user("watched@example.test");
        String memberToken = "Bearer " + new TestJwtFactory(jdbc, encoder).token(member, "MEMBER");
        jdbc.sql("""
                INSERT INTO access_session(user_id, created_at, expires_at, revoked_at, ip_address, user_agent)
                VALUES (:id, now() - interval '3 days', now() - interval '2 days', NULL, '198.51.100.9', 'Old Phone')
                """).param("id", member).update();
        // Gọi một API có xác thực để cập nhật last_seen_at của phiên hiện tại.
        mvc.perform(get("/api/v1/me").header("Authorization", memberToken)).andExpect(status().isOk());

        UUID list = jdbc.sql("INSERT INTO saved_list(owner_id, name) VALUES (:id, 'Yêu thích') RETURNING id").param("id", member)
                .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO analytics_event(event_id, name, occurred_at, session_id, platform, user_id, object_id)
                VALUES (gen_random_uuid(), 'place_viewed', now() - interval '1 hour', 's1', 'web', :id, 'P-1'),
                       (gen_random_uuid(), 'search_performed', now(), 's1', 'web', :id, NULL)
                """).param("id", member).update();

        mvc.perform(get("/api/v1/admin/users/{id}", member).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("watched@example.test"))
                .andExpect(jsonPath("$.activeSessions").value(1))
                .andExpect(jsonPath("$.mfaEnrolled").value(false))
                .andExpect(jsonPath("$.lastLoginAt").exists())
                .andExpect(jsonPath("$.lastSeenAt").exists());

        mvc.perform(get("/api/v1/admin/users/{id}/sessions", member).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.items[0].lastSeenAt").exists())
                .andExpect(jsonPath("$.items[1].ipAddress").value("198.51.100.9"))
                .andExpect(jsonPath("$.items[1].active").value(false));

        mvc.perform(get("/api/v1/admin/users/{id}/activity", member).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedLists").value(1))
                .andExpect(jsonPath("$.plansScheduled").value(0))
                .andExpect(jsonPath("$.recentEvents.length()").value(2))
                .andExpect(jsonPath("$.recentEvents[0].name").value("search_performed"));

        mvc.perform(post("/api/v1/admin/users/{id}/sessions/revoke", member).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", memberToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users/{id}", member).header("Authorization", adminToken))
                .andExpect(jsonPath("$.activeSessions").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(get("/api/v1/admin/users/{id}/activity", UUID.randomUUID()).header("Authorization", adminToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/users/{id}/sessions", member).header("Authorization", memberToken))
                .andExpect(status().isUnauthorized());
        assertThat(list).isNotNull();
        mvc.perform(get("/api/v1/admin/users/{id}", member).header("Authorization", adminToken))
                .andExpect(jsonPath("$.email", containsString("watched")));
    }
}
