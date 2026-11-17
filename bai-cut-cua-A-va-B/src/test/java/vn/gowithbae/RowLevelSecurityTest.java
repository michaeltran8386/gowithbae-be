package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Mọi bảng trong public phải bật RLS để Supabase Data API không đọc được dữ liệu (xem V3). */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
class RowLevelSecurityTest {

    @Autowired JdbcClient jdbc;

    @Test
    void everyPublicTableHasRowLevelSecurity() {
        List<String> missing = jdbc.sql("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND NOT rowsecurity AND tablename <> 'flyway_schema_history'
                """).query(String.class).list();
        assertThat(missing).as("Bảng thiếu ENABLE ROW LEVEL SECURITY").isEmpty();
    }

    @Test
    void ownerSeesRowsButOtherRoleWithSelectGrantSeesNone() {
        jdbc.sql("INSERT INTO area (code, name) VALUES ('RLS_CHECK', 'x') ON CONFLICT DO NOTHING").update();
        assertThat(jdbc.sql("SELECT count(*) FROM area WHERE code = 'RLS_CHECK'").query(Long.class).single())
                .isEqualTo(1L);

        // Giả lập role anon của Supabase: có quyền SELECT nhưng không có policy ⇒ 0 dòng.
        jdbc.sql("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fake_anon') THEN CREATE ROLE fake_anon; END IF; END $$").update();
        jdbc.sql("GRANT SELECT ON area TO fake_anon").update();
        Long seenByAnon = tx.execute(s -> {
            jdbc.sql("SET LOCAL ROLE fake_anon").update();
            return jdbc.sql("SELECT count(*) FROM area").query(Long.class).single();
        });
        assertThat(seenByAnon).isZero();
    }

    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
}
