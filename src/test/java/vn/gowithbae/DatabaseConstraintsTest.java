package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ràng buộc và index của V404 hoạt động trên Postgres thật. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="})
class DatabaseConstraintsTest {

    @Autowired JdbcClient jdbc;

    UUID user;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE audit_log, notification, saved_list_item, saved_list, app_user CASCADE").update();
        user = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('db@test.invalid', 'DB') RETURNING id").query(UUID.class).single();
    }

    @Test
    void auditLogIsAppendOnly() {
        long id = jdbc.sql("INSERT INTO audit_log(actor_id, action, object_type) VALUES (:u, 'TEST', 'x') RETURNING id")
                .param("u", user).query(Long.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET reason = 'sửa' WHERE id = :id").param("id", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).rootCause().hasMessageContaining("không được sửa hoặc xóa");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log WHERE id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void unknownNotificationTypeAndBlankRequiredTextAreRejected() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO notification(recipient_id, type, summary) VALUES (:u, 'SOMETHING_ELSE', 'x')")
                .param("u", user).update()).isInstanceOf(DataAccessException.class);
        jdbc.sql("INSERT INTO notification(recipient_id, type, summary) VALUES (:u, 'DATE_PLAN_INVITE_RESPONDED', 'x')").param("u", user).update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO saved_list(owner_id, name) VALUES (:u, '   ')").param("u", user).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void allV404ConstraintsAreValidatedOnACleanDatabase() {
        List<String> notValidated = jdbc.sql("""
                SELECT conname FROM pg_constraint
                WHERE conname IN ('ck_notification_type', 'ck_place_name_not_blank', 'ck_place_image_rights_not_blank',
                                  'ck_support_message_body_not_blank', 'ck_saved_list_name_not_blank')
                  AND NOT convalidated
                """).query(String.class).list();
        assertThat(notValidated).isEmpty();
    }

    @Test
    void searchTextHasTrigramIndex() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_place_search_text_trgm'").query(String.class).single())
                .contains("gin_trgm_ops");
    }
}
