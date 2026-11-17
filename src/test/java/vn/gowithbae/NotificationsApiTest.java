package vn.gowithbae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.gowithbae.platform.NotificationService;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="})
@AutoConfigureMockMvc
class NotificationsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired NotificationService notifications;
    private String token;
    private String otherToken;
    private UUID first;
    private UUID second;
    private UUID foreign;

    @BeforeEach
    void seed() {
        UUID owner = newUser();
        UUID other = newUser();
        var jwt = new TestJwtFactory(jdbc, encoder);
        token = jwt.token(owner, "MEMBER");
        otherToken = jwt.token(other, "MEMBER");
        first = notifications.notify(owner, NotificationService.Type.SUPPORT_REPLIED, "SUPPORT_TICKET", UUID.randomUUID(), "First");
        second = notifications.notify(owner, NotificationService.Type.DATE_PLAN_UPDATED, "DATE_PLAN", UUID.randomUUID(), "Second");
        foreign = notifications.notify(other, NotificationService.Type.SUPPORT_STATUS_CHANGED, "SUPPORT_TICKET", UUID.randomUUID(), "Private");
        jdbc.sql("UPDATE notification SET created_at = now() - interval '1 day' WHERE id = :id").param("id", first).update();
    }

    private UUID newUser() {
        return jdbc.sql("INSERT INTO app_user(email, display_name) VALUES (:email, 'Test member') RETURNING id")
                .param("email", UUID.randomUUID() + "@notifications.test").query(UUID.class).single();
    }

    @Test
    void paginationAndUnreadCountAreRecipientScopedAndReadDoesNotHappenOnGet() throws Exception {
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.unreadCount").value(2));
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.unreadCount").value(2));
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void markReadIsIdempotentAndCannotReadAnotherRecipientsNotification() throws Exception {
        mvc.perform(post("/api/v1/notifications/{id}/read", first).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        OffsetDateTime read = jdbc.sql("SELECT read_at FROM notification WHERE id = :id").param("id", first).query(OffsetDateTime.class).single();
        mvc.perform(post("/api/v1/notifications/{id}/read", first).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertEquals(read, jdbc.sql("SELECT read_at FROM notification WHERE id = :id").param("id", first).query(OffsetDateTime.class).single());
        mvc.perform(post("/api/v1/notifications/{id}/read", foreign).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/api/v1/notifications/{id}/read", UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void emptyInboxAndInvalidPaginationAndAuthentication() throws Exception {
        String emptyToken = new TestJwtFactory(jdbc, encoder).token(newUser(), "MEMBER");
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + emptyToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0)).andExpect(jsonPath("$.unreadCount").value(0));
        for (String size : new String[]{"0", "101"}) {
            mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token).param("size", size))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/{id}/read", first)).andExpect(status().isUnauthorized());
    }
}
