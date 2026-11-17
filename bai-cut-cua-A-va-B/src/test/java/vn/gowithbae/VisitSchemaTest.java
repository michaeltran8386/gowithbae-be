package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** CR-001: kiểm tra ràng buộc DB, không giả là đã implement API lời nhắc. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
class VisitSchemaTest {
    @Autowired JdbcClient jdbc;
    UUID userId;
    UUID planId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE app_user, place, area CASCADE").update();
        userId = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('visit@test.invalid', 'Linh') RETURNING id")
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO area(code, name) VALUES ('VISIT', 'Khu minh họa')").update();
        UUID placeId = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, status)
                SELECT 'VISIT', 'Gốm minh họa', t.id, a.id, 'Minh họa', 10.77, 106.70, 'PUBLISHED'
                FROM taxonomy t, area a WHERE t.code = 'CAFE' AND a.code = 'VISIT'
                RETURNING id
                """).query(UUID.class).single();
        planId = jdbc.sql("""
                INSERT INTO date_plan(owner_id, place_id, scheduled_at)
                VALUES (:user, :place, now()) RETURNING id
                """).param("user", userId).param("place", placeId).query(UUID.class).single();
    }

    @Test
    void oneVisitPerPlanAndUserButDifferentPlansCanContribute() {
        String report = "INSERT INTO visit_self_report(plan_id, respondent_user_id, answer) VALUES (:plan, :user, 'YES')";
        jdbc.sql(report).param("plan", planId).param("user", userId).update();
        assertThatThrownBy(() -> jdbc.sql(report).param("plan", planId).param("user", userId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID otherPlan = jdbc.sql("""
                INSERT INTO date_plan(owner_id, place_id, scheduled_at)
                SELECT owner_id, place_id, scheduled_at FROM date_plan WHERE id = :plan RETURNING id
                """).param("plan", planId).query(UUID.class).single();
        jdbc.sql(report).param("plan", otherPlan).param("user", userId).update();
        assertThat(jdbc.sql("SELECT count(*) FROM visit_self_report WHERE respondent_user_id = :user")
                .param("user", userId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void dismissalIsStoredOnceAndDoesNotCreateVisitOrChangePlan() {
        String dismiss = """
                INSERT INTO date_reminder(plan_id, recipient_user_id, scheduled_at, status, dismissed_at)
                SELECT id, owner_id, scheduled_at, 'DISMISSED', now() FROM date_plan WHERE id = :plan
                """;
        jdbc.sql(dismiss).param("plan", planId).update();
        assertThatThrownBy(() -> jdbc.sql(dismiss).param("plan", planId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT status FROM date_reminder WHERE plan_id = :plan")
                .param("plan", planId).query(String.class).single()).isEqualTo("DISMISSED");
        assertThat(jdbc.sql("SELECT count(*) FROM visit_self_report").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM date_plan WHERE id = :plan")
                .param("plan", planId).query(String.class).single()).isEqualTo("SCHEDULED");
    }

    @Test
    void reminderRequiresOneRecipientAndTimestampForDismissedStatus() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO date_reminder(plan_id, scheduled_at) VALUES (:plan, now())
                """).param("plan", planId).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO date_reminder(plan_id, recipient_user_id, scheduled_at, status)
                VALUES (:plan, :user, now(), 'DISMISSED')
                """).param("plan", planId).param("user", userId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
