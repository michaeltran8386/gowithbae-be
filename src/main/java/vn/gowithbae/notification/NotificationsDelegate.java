package vn.gowithbae.notification;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.NotificationsApiDelegate;
import vn.gowithbae.api.model.Notification;
import vn.gowithbae.api.model.NotificationPage;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.platform.CurrentUser;

@Service
public class NotificationsDelegate implements NotificationsApiDelegate {
    private final JdbcClient jdbc;
    private final CurrentUser user;

    public NotificationsDelegate(JdbcClient jdbc, CurrentUser user) {
        this.jdbc = jdbc;
        this.user = user;
    }

    private UUID recipient() {
        user.requireActive();
        return user.id();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<NotificationPage> listNotifications(Integer page, Integer size) {
        UUID recipient = recipient();
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        var counts = jdbc.sql("""
                SELECT count(*) AS total, count(*) FILTER (WHERE read_at IS NULL) AS unread
                FROM notification WHERE recipient_id = :recipient
                """).param("recipient", recipient).query((rs, n) -> new long[]{rs.getLong("total"), rs.getLong("unread")}).single();
        var items = jdbc.sql("""
                SELECT * FROM notification WHERE recipient_id = :recipient
                ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
                """).param("recipient", recipient).param("size", s).param("offset", (long) p * s)
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class),
                        Notification.TypeEnum.fromValue(rs.getString("type")), rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class))
                        .objectType(rs.getString("object_type")).objectId(rs.getObject("object_id", UUID.class))
                        .readAt(rs.getObject("read_at", OffsetDateTime.class))).list();
        // Target endpoints recheck current permissions; notification metadata grants no access.
        return ResponseEntity.ok(new NotificationPage(items,
                new PageMeta(p, s, counts[0], Math.toIntExact((counts[0] + s - 1) / s)), Math.toIntExact(counts[1])));
    }

    @Override
    public ResponseEntity<Void> markNotificationRead(UUID id) {
        int updated = jdbc.sql("""
                UPDATE notification SET read_at = COALESCE(read_at, now())
                WHERE id = :id AND recipient_id = :recipient
                """).param("id", id).param("recipient", recipient()).update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return ResponseEntity.noContent().build();
    }
}
