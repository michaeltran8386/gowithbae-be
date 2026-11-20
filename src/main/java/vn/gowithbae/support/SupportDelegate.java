package vn.gowithbae.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.SupportApiDelegate;
import vn.gowithbae.api.model.MessageCreate;
import vn.gowithbae.api.model.MessageVisibility;
import vn.gowithbae.api.model.SupportMessage;
import vn.gowithbae.api.model.SupportTicket;
import vn.gowithbae.api.model.SupportTicketCreate;
import vn.gowithbae.api.model.SupportTicketDetail;
import vn.gowithbae.api.model.TicketStatus;
import vn.gowithbae.platform.CurrentUser;

@Service
public class SupportDelegate implements SupportApiDelegate {

    private final JdbcClient jdbc;
    private final CurrentUser current;

    public SupportDelegate(JdbcClient jdbc, CurrentUser current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @Override
    @Transactional
    public ResponseEntity<SupportTicketDetail> createTicket(SupportTicketCreate req) {
        current.requireActive();
        UUID userId = current.id();

        UUID ticketId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String category = req.getCategory().getValue();

        jdbc.sql("""
                INSERT INTO support_ticket (id, owner_id, category, subject, status, created_at, updated_at)
                VALUES (:id, :ownerId, :cat, :sub, 'NEW', :now, :now)
                """)
                .param("id", ticketId)
                .param("ownerId", userId)
                .param("cat", category)
                .param("sub", req.getSubject())
                .param("now", now)
                .update();

        UUID messageId = UUID.randomUUID();
        String body = req.getBody() != null ? req.getBody() : "";

        jdbc.sql("""
                INSERT INTO support_message (id, ticket_id, author_id, visibility, body, created_at)
                VALUES (:id, :ticketId, :authorId, 'PUBLIC', :body, :now)
                """)
                .param("id", messageId)
                .param("ticketId", ticketId)
                .param("authorId", userId)
                .param("body", body)
                .param("now", now)
                .update();

        String authorName = jdbc.sql("SELECT display_name FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).optional().orElse("Người dùng");

        SupportMessage firstMsg = new SupportMessage(messageId, authorName, MessageVisibility.PUBLIC, body, now);
        firstMsg.setFromStaff(false);

        SupportTicketDetail detail = new SupportTicketDetail(
                ticketId,
                category,
                req.getSubject(),
                TicketStatus.NEW,
                now,
                now,
                List.of(firstMsg)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @Override
    public ResponseEntity<List<SupportTicket>> listMyTickets() {
        current.requireActive();
        UUID userId = current.id();

        List<SupportTicket> list = jdbc.sql("""
                SELECT t.id, t.category, t.subject, t.status, t.created_at, t.updated_at,
                       u.display_name AS assignee_name
                FROM support_ticket t
                LEFT JOIN app_user u ON u.id = t.assignee_id
                WHERE t.owner_id = :userId
                ORDER BY t.updated_at DESC
                """)
                .param("userId", userId)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String cat = rs.getString("category");
                    String sub = rs.getString("subject");
                    String stat = rs.getString("status");
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
                    String assignee = rs.getString("assignee_name");

                    SupportTicket st = new SupportTicket(id, cat, sub, TicketStatus.fromValue(stat), createdAt, updatedAt);
                    st.setAssigneeName(assignee);
                    return st;
                }).list();

        return ResponseEntity.ok(list);
    }

    @Override
    public ResponseEntity<SupportTicketDetail> getMyTicket(UUID ticketId) {
        current.requireActive();
        UUID userId = current.id();

        var ticketOpt = jdbc.sql("""
                SELECT t.id, t.category, t.subject, t.status, t.created_at, t.updated_at,
                       u.display_name AS assignee_name
                FROM support_ticket t
                LEFT JOIN app_user u ON u.id = t.assignee_id
                WHERE t.id = :id AND t.owner_id = :userId
                """)
                .param("id", ticketId)
                .param("userId", userId)
                .query((rs, i) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("category"),
                        rs.getString("subject"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("assignee_name")
                })
                .optional();

        if (ticketOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ");
        }

        Object[] t = ticketOpt.get();

        List<SupportMessage> messages = jdbc.sql("""
                SELECT m.id, m.author_id, m.body, m.created_at,
                       u.display_name,
                       EXISTS(SELECT 1 FROM user_role r WHERE r.user_id = m.author_id AND r.role_code <> 'MEMBER') AS from_staff
                FROM support_message m
                JOIN app_user u ON u.id = m.author_id
                WHERE m.ticket_id = :tid AND m.visibility = 'PUBLIC'
                ORDER BY m.created_at ASC
                """)
                .param("tid", ticketId)
                .query((rs, i) -> {
                    UUID mid = rs.getObject("id", UUID.class);
                    String name = rs.getString("display_name");
                    String body = rs.getString("body");
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    boolean fromStaff = rs.getBoolean("from_staff");

                    SupportMessage sm = new SupportMessage(mid, name, MessageVisibility.PUBLIC, body, createdAt);
                    sm.setFromStaff(fromStaff);
                    return sm;
                }).list();

        SupportTicketDetail detail = new SupportTicketDetail(
                (UUID) t[0],
                (String) t[1],
                (String) t[2],
                TicketStatus.fromValue((String) t[3]),
                (OffsetDateTime) t[4],
                (OffsetDateTime) t[5],
                messages
        );
        detail.setAssigneeName((String) t[6]);

        return ResponseEntity.ok(detail);
    }

    @Override
    @Transactional
    public ResponseEntity<SupportMessage> replyMyTicket(UUID ticketId, MessageCreate req) {
        current.requireActive();
        UUID userId = current.id();

        boolean exists = jdbc.sql("SELECT count(*) FROM support_ticket WHERE id = :id AND owner_id = :userId")
                .param("id", ticketId).param("userId", userId).query(Long.class).single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ");
        }

        UUID messageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO support_message (id, ticket_id, author_id, visibility, body, created_at)
                VALUES (:id, :tid, :uid, 'PUBLIC', :body, :now)
                """)
                .param("id", messageId)
                .param("tid", ticketId)
                .param("uid", userId)
                .param("body", req.getBody())
                .param("now", now)
                .update();

        jdbc.sql("UPDATE support_ticket SET updated_at = now() WHERE id = :id").param("id", ticketId).update();

        String authorName = jdbc.sql("SELECT display_name FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).optional().orElse("Người dùng");

        SupportMessage sm = new SupportMessage(messageId, authorName, MessageVisibility.PUBLIC, req.getBody(), now);
        sm.setFromStaff(false);

        return ResponseEntity.status(HttpStatus.CREATED).body(sm);
    }
}
