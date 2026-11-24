package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.auth.OtpDelivery;

@Testcontainers(disabledWithoutDocker = true)
@Import({TestcontainersConfiguration.class, AuthApiTest.MailboxConfiguration.class})
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=",
        "app.auth.resend-cooldown=PT0S", "app.auth.member-otp-enabled=true"})
@AutoConfigureMockMvc
class AuthApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired Mailbox mail;
    @Autowired vn.gowithbae.auth.StaffMfa mfa;
    @Autowired org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    @Autowired vn.gowithbae.auth.DatabaseJwtAuthenticationConverter converter;

    static class Mailbox implements OtpDelivery {
        final Map<String,String> codes = new ConcurrentHashMap<>();
        public void send(String email, String code, String purpose) { codes.put(email, code); }
    }
    @TestConfiguration
    static class MailboxConfiguration {
        @Bean @Primary Mailbox mailbox() { return new Mailbox(); }
    }

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE app_user, login_challenge, request_rate_limit CASCADE").update();
        mail.codes.clear();
    }

    String login(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email)))).andExpect(status().isAccepted());
        String body = mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"code",mail.codes.get(email)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    @Test
    void emailOtpIsHashedAndSingleUseAndJwtOpensOwnProfile() throws Exception {
        String token = login("linh@example.test");
        String stored = jdbc.sql("SELECT code_hash FROM login_challenge").query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(mail.codes.get("linh@example.test"));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("linh@example.test"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"));
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email","linh@example.test","code",mail.codes.get("linh@example.test")))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM access_session").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void logoutRevokesCurrentSessionImmediately() throws Exception {
        String token = login("linh@example.test");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void wrongOtpAttemptIsCommittedAndLimitPreventsCorrectCodeAfterwards() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"an@example.test\"}"))
                .andExpect(status().isAccepted());
        String actual = mail.codes.get("an@example.test");
        String wrong = "000000".equals(actual) ? "111111" : "000000";
        for (int i=0;i<5;i++) mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email","an@example.test","code",wrong))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT attempts FROM login_challenge").query(Integer.class).single()).isEqualTo(5);
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email","an@example.test","code",actual))))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql("SELECT count(*) FROM access_session").query(Long.class).single()).isZero();
    }

    @Test
    void revokedExpiredAndLockedSessionsCannotAccessPrivateEndpoints() throws Exception {
        UUID user = jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('test@example.test','Test') RETURNING id").query(UUID.class).single();
        String token = new TestJwtFactory(jdbc,encoder).token(user,"MEMBER");
        jdbc.sql("UPDATE access_session SET expires_at=now()-interval '1 second' WHERE user_id=:id").param("id",user).update();
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        token = new TestJwtFactory(jdbc,encoder).token(user,"MEMBER");
        jdbc.sql("UPDATE app_user SET status='LOCKED' WHERE id=:id").param("id",user).update();
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }

    @Test
    void patchRejectsBlankNameAndOtherUsersAvatar() throws Exception {
        String token=login("linh@example.test");
        mvc.perform(patch("/api/v1/me").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"   \"}")).andExpect(status().isBadRequest());
        UUID other=jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('other@example.test','Other') RETURNING id").query(UUID.class).single();
        UUID file=jdbc.sql("INSERT INTO stored_file(owner_user_id,purpose,content_type,size_bytes,storage_key) VALUES (:id,'AVATAR','image/png',10,'private') RETURNING id")
                .param("id",other).query(UUID.class).single();
        mvc.perform(patch("/api/v1/me").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("avatarFileId",file.toString())))).andExpect(status().isNotFound());
    }

    @Test
    void invalidEmailDoesNotSendAnythingAndRequestLimitPersists() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        assertThat(mail.codes).isEmpty();
        for(int i=0;i<5;i++) mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"an@example.test\"}")).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"an@example.test\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void emailChangeRequiresNewEmailOtpAndDeletionRevokesAllSessions() throws Exception {
        String token=login("linh@example.test");
        mvc.perform(post("/api/v1/me/email-change").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"new@example.test\"}")).andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT email FROM app_user").query(String.class).single()).isEqualTo("linh@example.test");
        mvc.perform(post("/api/v1/me/email-change/verify").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code",mail.codes.get("new@example.test")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.test"));
        mvc.perform(post("/api/v1/me/deletion-request").header("Authorization","Bearer "+token)).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT status FROM app_user").query(String.class).single()).isEqualTo("DELETION_REQUESTED");
    }

    @Test
    void staffNeedsEmailAndTotpAndTotpStepCannotBeReplayed() throws Exception {
        String secret="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(vn.gowithbae.auth.StaffMfa.code(secret,1)).isEqualTo("287082");
        UUID id=jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('staff@example.test','Staff') RETURNING id").query(UUID.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES (:id,'SUPER_ADMIN')").param("id",id).update();
        mfa.enroll(id,secret);
        assertThat(jdbc.sql("SELECT secret_ciphertext FROM staff_mfa").query(String.class).single()).doesNotContain(secret);
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"staff@example.test\"}"))
                .andExpect(status().isAccepted());
        var body=new java.util.HashMap<String,String>();
        body.put("email","staff@example.test");body.put("code",mail.codes.get("staff@example.test"));
        // OTP đúng nhưng thiếu TOTP: 401 có mã riêng để form hiện ô nhập, OTP chưa bị tiêu thụ.
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STAFF_CODE_REQUIRED"));
        String totp=vn.gowithbae.auth.StaffMfa.code(secret,java.time.Instant.now().getEpochSecond()/30);
        body.put("staffCode",totp);
        String response=mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=json.readTree(response).get("accessToken").asString();
        mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"staff@example.test\"}"))
                .andExpect(status().isAccepted());
        body.put("code",mail.codes.get("staff@example.test"));
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lastSuperAdminCannotRequestDeletion() throws Exception {
        UUID id=jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('last@example.test','Last') RETURNING id").query(UUID.class).single();
        String token=new TestJwtFactory(jdbc,encoder).token(id,"SUPER_ADMIN");
        mvc.perform(post("/api/v1/me/deletion-request").header("Authorization","Bearer "+token)).andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT status FROM app_user WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void promotionAfterTokenValidationCannotGainStaffAccessWithoutMfa(){
        UUID id=jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('promoted@example.test','Promoted') RETURNING id").query(UUID.class).single();
        var jwt=decoder.decode(new TestJwtFactory(jdbc,encoder).token(id,"MEMBER"));
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES (:id,'SUPER_ADMIN')").param("id",id).update();
        assertThatThrownBy(()->converter.convert(jwt)).isInstanceOf(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class);
        jdbc.sql("UPDATE access_session SET revoked_at=now() WHERE user_id=:id").param("id",id).update();
        assertThatThrownBy(()->converter.convert(jwt)).isInstanceOf(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class);
    }

    @Test
    void varyingUnknownEmailsCannotEvadeIpVerificationLimit() throws Exception {
        for(int i=0;i<30;i++)mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email","missing"+i+"@example.test","code","000000"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new-address@example.test\",\"code\":\"000000\"}")).andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql("SELECT count(*) FROM request_rate_limit WHERE bucket='otp-verify'").query(Long.class).single()).isEqualTo(30);
    }

    @Test
    void concurrentVerificationConsumesOneOtpOnlyOnce() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"race@example.test\"}"))
                .andExpect(status().isAccepted());
        String body=json.writeValueAsString(Map.of("email","race@example.test","code",mail.codes.get("race@example.test")));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->(java.util.concurrent.Callable<Integer>)
                    ()->mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus()).toList();
            var results=new java.util.ArrayList<Integer>();
            for(var f:executor.invokeAll(tasks))results.add(f.get());
            assertThat(results).containsExactlyInAnyOrder(200,400);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM access_session").query(Long.class).single()).isEqualTo(1);
    }
}
