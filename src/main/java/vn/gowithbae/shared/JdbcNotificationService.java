package vn.gowithbae.shared;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi thông báo vào bảng notification ở transaction độc lập (REQUIRES_NEW) và nuốt lỗi phụ
 * để lỗi thông báo không làm hỏng thao tác nghiệp vụ đã lưu thành công (RULE-24, EX-04).
 */
@Service
public class JdbcNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(JdbcNotificationService.class);

    private final JdbcClient jdbc;

    public JdbcNotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(
            UUID recipientId,
            String type,
            @Nullable String objectType,
            @Nullable UUID objectId,
            String summary) {
        try {
            jdbc.sql("""
                    INSERT INTO notification(recipient_id, type, object_type, object_id, summary)
                    VALUES (:recipientId, :type, CAST(:objectType AS text), CAST(:objectId AS uuid), :summary)
                    """)
                    .param("recipientId", recipientId)
                    .param("type", type)
                    .param("objectType", objectType)
                    .param("objectId", objectId)
                    .param("summary", summary)
                    .update();
        } catch (RuntimeException ex) {
            log.warn("Notification side effect failed for recipient={} type={}", recipientId, type, ex);
        }
    }
}
