package vn.gowithbae;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
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

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="})
@AutoConfigureMockMvc
class AdminAccountsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper json;
    UUID admin, member;
    String token, memberToken;

    @BeforeEach void seed() {
        jdbc.sql("TRUNCATE app_user, login_challenge, analytics_event CASCADE").update();
        admin = user("admin"); member = user("member");
        TestJwtFactory factory = new TestJwtFactory(jdbc, encoder);
        token = factory.token(admin, "SUPER_ADMIN"); memberToken = factory.token(member, "MEMBER");
    }
    UUID user(String name) {
        return jdbc.sql("INSERT INTO app_user(email,display_name) VALUES (:email,:name) RETURNING id")
                .param("email", name + "@example.test").param("name", name).query(UUID.class).single();
    }
    @Test void superAdminOnlyAndFiltersAreLiteral() throws Exception {
        String support = new TestJwtFactory(jdbc, encoder).token(member, "SUPPORT");
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + support)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + support)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/users/" + member + "/roles").header("Authorization", "Bearer " + support)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"SUPER_ADMIN\"],\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token).param("q", "member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(member.toString()));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token).param("q", "%' OR true --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
    }
    @Test void lockRevokesSessionsAndPendingOtpAndSafeAuditThenUnlock() throws Exception {
        jdbc.sql("INSERT INTO login_challenge(email,purpose,code_hash,expires_at) VALUES ('member@example.test','LOGIN','secret-hash',now()+interval '5 minutes')").update();
        mvc.perform(post("/api/v1/admin/users/" + member + "/lock").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Policy violation\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"));
        assertThat(jdbc.sql("SELECT count(*) FROM access_session WHERE user_id=:id AND revoked_at IS NULL").param("id", member).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM login_challenge WHERE used_at IS NULL").query(Long.class).single()).isZero();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + memberToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + token)
                .param("objectType", "app_user").param("objectId", member.toString()).param("actorId", admin.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].before.status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].after.status").value("LOCKED"))
                .andExpect(jsonPath("$.items[0].before.email").doesNotExist())
                .andExpect(jsonPath("$.items[0].reason").value("Policy violation"));
        mvc.perform(post("/api/v1/admin/users/" + member + "/unlock").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Resolved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.sql("SELECT count(*) FROM access_session WHERE user_id=:id AND revoked_at IS NULL").param("id", member).query(Long.class).single()).isZero();
    }
    @Test void selfLockLastAdminAndSelfDemotionFailWithoutAudit() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + admin + "/lock").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/admin/users/" + admin + "/roles").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"MEMBER\"],\"reason\":\"test\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM user_role WHERE role_code='SUPER_ADMIN'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()).isZero();
    }
    @Test void roleChangesRevokeOtpAndSessionAndAuditOnlyRoles() throws Exception {
        jdbc.sql("INSERT INTO login_challenge(email,user_id,purpose,code_hash,expires_at) VALUES ('member@example.test',:id,'EMAIL_CHANGE','secret',now()+interval '5 minutes')")
                .param("id", member).update();
        mvc.perform(put("/api/v1/admin/users/" + member + "/roles").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"EDITOR\",\"MEMBER\"],\"reason\":\"Assign editor\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM login_challenge WHERE used_at IS NULL").query(Long.class).single()).isZero();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + memberToken)).andExpect(status().isUnauthorized());
        String after = jdbc.sql("SELECT after_value::text FROM audit_log WHERE object_id=:id").param("id", member.toString()).query(String.class).single();
        assertThat(json.readTree(after).size()).isEqualTo(1);
        assertThat(after).contains("EDITOR").doesNotContain("email", "secret", "token");
    }
    @Test void blankReasonAndDeletionStateAndBadPaginationAreRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + member + "/lock").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE app_user SET status='DELETION_REQUESTED', deletion_requested_at=now() WHERE id=:id").param("id", member).update();
        mvc.perform(post("/api/v1/admin/users/" + member + "/unlock").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test void simultaneousCrossDemotionsLeaveOneActiveSuperAdmin() throws Exception {
        String otherToken = new TestJwtFactory(jdbc, encoder).token(member, "SUPER_ADMIN");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(() -> demoteAfter(start, token, member));
            Future<Integer> second = pool.submit(() -> demoteAfter(start, otherToken, admin));
            start.countDown();
            List<Integer> statuses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(statuses).allMatch(code -> code == 200 || code == 401 || code == 403 || code == 409);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_user u JOIN user_role r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='SUPER_ADMIN'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action='USER_ROLES_CHANGED'").query(Long.class).single()).isEqualTo(1);
    }

    int demoteAfter(CountDownLatch start, String bearer, UUID target) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return mvc.perform(put("/api/v1/admin/users/" + target + "/roles").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"MEMBER\"],\"reason\":\"Reassign\"}"))
                .andReturn().getResponse().getStatus();
    }
}
