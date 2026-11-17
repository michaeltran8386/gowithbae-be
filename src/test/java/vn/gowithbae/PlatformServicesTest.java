package vn.gowithbae;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.RequestRateLimiter;

@Testcontainers(disabledWithoutDocker=true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"app.jwt.secret=test-secret-test-secret-test-secret-123","DB_URL=","DB_USERNAME=","DB_PASSWORD="})
class PlatformServicesTest {
    @Autowired JdbcClient jdbc;
    @Autowired AuditService audit;
    @Autowired RequestRateLimiter limiter;
    UUID actor;
    @BeforeEach void seed(){
        jdbc.sql("TRUNCATE app_user,request_rate_limit CASCADE").update();
        actor=jdbc.sql("INSERT INTO app_user(email,display_name) VALUES ('platform@example.test','Platform') RETURNING id").query(UUID.class).single();
    }
    @Test void auditRejectsNestedSecretsBeforePersistingAndKeepsSafeChanges(){
        assertThatThrownBy(()->audit.log(actor,"TEST","app_user",actor.toString(),null,
                Map.of("nested",Map.of("access_token","never-store")),"test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()).isZero();
        audit.log(actor,"USER_LOCKED","app_user",actor.toString(),Map.of("status","ACTIVE"),Map.of("status","LOCKED"),"test");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()).isEqualTo(1);
    }
    @Test void concurrentRateRequestsCannotExceedLimitAndDoNotStoreRawKey() throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)){
            var tasks=java.util.stream.IntStream.range(0,12).mapToObj(i->(java.util.concurrent.Callable<Boolean>)
                    ()->limiter.consume("concurrent","private@example.test",3,Duration.ofHours(1))).toList();
            long success=0;
            for(var future:executor.invokeAll(tasks))if(future.get())success++;
            assertThat(success).isEqualTo(3);
        }
        assertThat(jdbc.sql("SELECT key_hash FROM request_rate_limit").query(String.class).single())
                .hasSize(64).doesNotContain("private@example.test");
    }
    @Test void expiredRateWindowsAreCleanedWithoutResettingLiveLimits(){
        jdbc.sql("INSERT INTO request_rate_limit(bucket,key_hash,window_start,hits) VALUES ('old','hash',now()-interval '3 days',1)").update();
        assertThat(limiter.consume("live","key",1,Duration.ofHours(1))).isTrue();
        limiter.cleanupExpiredWindows();
        assertThat(jdbc.sql("SELECT count(*) FROM request_rate_limit WHERE bucket='old'").query(Long.class).single()).isZero();
        assertThat(limiter.consume("live","key",1,Duration.ofHours(1))).isFalse();
    }
}
