package vn.gowithbae.platform;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableScheduling
public class RequestRateLimiter {
    private final JdbcClient jdbc;
    private final SecretHash hash;
    public RequestRateLimiter(JdbcClient jdbc, SecretHash hash) { this.jdbc = jdbc; this.hash = hash; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String bucket, String key, int limit, Duration window) {
        long seconds = window.getSeconds();
        if (limit < 1 || seconds < 1 || seconds > 86400) throw new IllegalArgumentException("Hạn mức phải dương, cửa sổ tối đa 24 giờ");
        long now = Instant.now().getEpochSecond();
        var start = OffsetDateTime.ofInstant(Instant.ofEpochSecond(now / seconds * seconds), ZoneOffset.UTC);
        return jdbc.sql("""
                INSERT INTO request_rate_limit(bucket, key_hash, window_start, hits)
                VALUES (:bucket, :key, :start, 1)
                ON CONFLICT (bucket, key_hash, window_start) DO UPDATE SET hits = request_rate_limit.hits + 1
                WHERE request_rate_limit.hits < :limit RETURNING hits
                """).param("bucket", bucket).param("key", hash.digest(bucket + ":" + key)).param("start", start)
                .param("limit", limit).query(Integer.class).optional().isPresent();
    }

    @Scheduled(fixedDelayString="${app.auth.rate-limit-cleanup-ms:3600000}",initialDelayString="${app.auth.rate-limit-cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpiredWindows(){
        jdbc.sql("DELETE FROM request_rate_limit WHERE window_start<now()-interval '2 days'").update();
    }
}
