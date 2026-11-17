package vn.gowithbae.support;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.AdminReplyTicketRequest;
import vn.gowithbae.api.model.AdminTransitionTicketRequest;
import vn.gowithbae.api.model.MessageCreate;
import vn.gowithbae.api.model.MessageVisibility;
import vn.gowithbae.api.model.StoredFile;
import vn.gowithbae.api.model.SupportMessage;
import vn.gowithbae.api.model.SupportTicket;
import vn.gowithbae.api.model.SupportTicketCreate;
import vn.gowithbae.api.model.SupportTicketDetail;
import vn.gowithbae.api.model.SupportTicketPage;
import vn.gowithbae.api.model.TicketStatus;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.UserPrincipal;
import vn.gowithbae.common.PageUtil;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.NotificationService;
import vn.gowithbae.shared.StoredFileLookup;

/**
 * Nghiệp vụ yêu cầu hỗ trợ (thành viên và nhân sự hỗ trợ) tách bạch PUBLIC/INTERNAL,
 * dùng AuditService, NotificationService và FileService từ vn.gowithbae.platform của Người A.
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    private final JdbcClient jdbc;
    private final ActorResolver actorResolver;
    private final StoredFileLookup storedFileLookup;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNewTx;

    public SupportService(
            JdbcClient jdbc,
            ActorResolver actorResolver,
            StoredFileLookup storedFileLookup,
            AuditService auditService,
            NotificationService notificationService,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.storedFileLookup = storedFileLookup;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<SupportTicket> listMyTickets() {
        UserPrincipal user = actorResolver.requireActiveUser();
        return jdbc.sql("""
                SELECT st.id, st.owner_id, st.category, st.subject, st.status,
                       assignee.display_name AS assignee_name, st.created_at, st.updated_at
                FROM support_ticket st
                LEFT JOIN app_user assignee ON assignee.id = st.assignee_id
                WHERE st.owner_id = :ownerId
                ORDER BY st.updated_at DESC
                """)
                .param("ownerId", user.userId())
                .query((rs, rowNum) -> toTicketSummary(mapTicketRow(rs)))
                .list();
    }

    @Transactional
    public SupportTicketDetail createTicket(SupportTicketCreate req) {
        UserPrincipal user = actorResolver.requireActiveUser();
        String subject = req.getSubject() == null ? "" : req.getSubject().trim();
        String body = req.getBody() == null ? "" : req.getBody().trim();
        if (subject.isEmpty() || body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tiêu đề và nội dung không được để trống");
        }

        List<UUID> fileIds = normalizeFileIds(req.getFileIds());
        storedFileLookup.validateSupportFiles(user.userId(), fileIds);

        TicketRow ticket = jdbc.sql("""
                INSERT INTO support_ticket(owner_id, category, subject, status)
                VALUES (:ownerId, :category, :subject, 'NEW')
                RETURNING id, owner_id, category, subject, status, NULL::text AS assignee_name, created_at, updated_at
                """)
                .param("ownerId", user.userId())
                .param("category", req.getCategory().getValue())
                .param("subject", subject)
                .query((rs, rowNum) -> mapTicketRow(rs))
                .single();

        SupportMessage firstMessage = insertMessage(ticket.id(), user.userId(), user.displayName(), false,
                MessageVisibility.PUBLIC, body, fileIds);
        return toTicketDetail(ticket, List.of(firstMessage));
    }

    public SupportTicketDetail getMyTicket(UUID ticketId) {
        UserPrincipal user = actorResolver.requireActiveUser();
        TicketRow ticket = jdbc.sql("""
                SELECT st.id, st.owner_id, st.category, st.subject, st.status,
                       assignee.display_name AS assignee_name, st.created_at, st.updated_at
                FROM support_ticket st
                LEFT JOIN app_user assignee ON assignee.id = st.assignee_id
                WHERE st.id = :ticketId AND st.owner_id = :ownerId
                """)
                .param("ticketId", ticketId)
                .param("ownerId", user.userId())
                .query((rs, rowNum) -> mapTicketRow(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ"));

        // RULE-19 / AC-049: thành viên chỉ nhận tin nhắn PUBLIC, tuyệt đối không lộ ghi chú INTERNAL.
        List<SupportMessage> messages = loadMessages(ticket.id(), ticket.ownerId(), false);
        return toTicketDetail(ticket, messages);
    }

    @Transactional
    public SupportMessage replyMyTicket(UUID ticketId, MessageCreate req) {
        UserPrincipal user = actorResolver.requireActiveUser();
        TicketRow ticket = jdbc.sql("""
                SELECT st.id, st.owner_id, st.category, st.subject, st.status,
                       assignee.display_name AS assignee_name, st.created_at, st.updated_at
                FROM support_ticket st
                LEFT JOIN app_user assignee ON assignee.id = st.assignee_id
                WHERE st.id = :ticketId AND st.owner_id = :ownerId
                FOR UPDATE OF st
                """)
                .param("ticketId", ticketId)
                .param("ownerId", user.userId())
                .query((rs, rowNum) -> mapTicketRow(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ"));

        if ("CLOSED".equals(ticket.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu hỗ trợ đã đóng, không thể phản hồi thêm");
        }

        String body = req.getBody() == null ? "" : req.getBody().trim();
        if (body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung phản hồi không được để trống");
        }
        List<UUID> fileIds = normalizeFileIds(req.getFileIds());
        storedFileLookup.validateSupportFiles(user.userId(), fileIds);

        SupportMessage message = insertMessage(ticket.id(), user.userId(), user.displayName(), false,
                MessageVisibility.PUBLIC, body, fileIds);

        jdbc.sql("""
                UPDATE support_ticket
                SET status = CASE WHEN status = 'WAITING_CUSTOMER' THEN 'IN_PROGRESS' ELSE status END,
                    updated_at = now()
                WHERE id = :ticketId
                """)
                .param("ticketId", ticketId)
                .update();

        return message;
    }

    public SupportTicketPage adminListTickets(
            @Nullable TicketStatus status,
            @Nullable UUID assigneeId,
            @Nullable Integer page,
            @Nullable Integer size) {

        int sanitizedPage = PageUtil.sanitizePage(page);
        int sanitizedSize = PageUtil.sanitizeSize(size);
        int offset = sanitizedPage * sanitizedSize;
        String statusStr = status == null ? null : status.getValue();

        Long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM support_ticket st
                WHERE (CAST(:status AS text) IS NULL OR st.status = CAST(:status AS text))
                  AND (CAST(:assigneeId AS uuid) IS NULL OR st.assignee_id = CAST(:assigneeId AS uuid))
                """)
                .param("status", statusStr)
                .param("assigneeId", assigneeId)
                .query(Long.class)
                .single();

        List<SupportTicket> items = jdbc.sql("""
                SELECT st.id, st.owner_id, st.category, st.subject, st.status,
                       assignee.display_name AS assignee_name, st.created_at, st.updated_at
                FROM support_ticket st
                LEFT JOIN app_user assignee ON assignee.id = st.assignee_id
                WHERE (CAST(:status AS text) IS NULL OR st.status = CAST(:status AS text))
                  AND (CAST(:assigneeId AS uuid) IS NULL OR st.assignee_id = CAST(:assigneeId AS uuid))
                ORDER BY st.updated_at DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("status", statusStr)
                .param("assigneeId", assigneeId)
                .param("limit", sanitizedSize)
                .param("offset", offset)
                .query((rs, rowNum) -> toTicketSummary(mapTicketRow(rs)))
                .list();

        return new SupportTicketPage(items, PageUtil.buildMeta(sanitizedPage, sanitizedSize, total == null ? 0L : total));
    }

    public SupportTicketDetail adminGetTicket(UUID ticketId) {
        TicketRow ticket = findTicketById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ"));
        List<SupportMessage> messages = loadMessages(ticket.id(), ticket.ownerId(), true);
        return toTicketDetail(ticket, messages);
    }

    @Transactional
    public SupportMessage adminReplyTicket(UUID ticketId, AdminReplyTicketRequest req) {
        UserPrincipal admin = actorResolver.requireActiveUser();
        TicketRow ticket = lockTicketById(ticketId);

        if ("CLOSED".equals(ticket.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu hỗ trợ đã đóng");
        }

        String body = req.getBody() == null ? "" : req.getBody().trim();
        if (body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung phản hồi không được để trống");
        }
        List<UUID> fileIds = normalizeFileIds(req.getFileIds());
        storedFileLookup.validateSupportFiles(admin.userId(), fileIds);

        boolean isPublic = req.getVisibility() == MessageVisibility.PUBLIC;
        SupportMessage message = insertMessage(ticket.id(), admin.userId(), admin.displayName(), true,
                req.getVisibility(), body, fileIds);

        jdbc.sql("""
                UPDATE support_ticket
                SET status = CASE WHEN status = 'NEW' AND :isPublic THEN 'IN_PROGRESS' ELSE status END,
                    updated_at = now()
                WHERE id = :ticketId
                """)
                .param("isPublic", isPublic)
                .param("ticketId", ticketId)
                .update();

        // Chỉ gửi thông báo cho khách khi phản hồi PUBLIC; không bao giờ gửi hoặc lộ ghi chú INTERNAL (AC-049/050).
        if (isPublic && !ticket.ownerId().equals(admin.userId())) {
            safeNotify(
                    ticket.ownerId(),
                    NotificationService.Type.SUPPORT_REPLIED,
                    "SUPPORT_TICKET",
                    ticketId,
                    "Yêu cầu hỗ trợ có phản hồi mới: " + ticket.subject());
        }

        return message;
    }

    @Transactional
    public SupportTicketDetail adminTransitionTicket(UUID ticketId, AdminTransitionTicketRequest req) {
        UserPrincipal admin = actorResolver.requireActiveUser();
        TicketRow before = lockTicketById(ticketId);

        if ("CLOSED".equals(before.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu hỗ trợ đã đóng, không thể mở lại");
        }

        if (req.getAssigneeId() != null) {
            Boolean assigneeExists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :uid)")
                    .param("uid", req.getAssigneeId())
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(assigneeExists)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhân sự phụ trách không tồn tại");
            }
        }

        TicketStatus nextStatus = req.getStatus();
        jdbc.sql("""
                UPDATE support_ticket
                SET status = :status,
                    assignee_id = COALESCE(CAST(:assigneeId AS uuid), assignee_id),
                    updated_at = now()
                WHERE id = :ticketId
                """)
                .param("status", nextStatus.getValue())
                .param("assigneeId", req.getAssigneeId())
                .param("ticketId", ticketId)
                .update();

        TicketRow updated = findTicketById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ"));

        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("status", before.status());
        beforeMap.put("assigneeName", before.assigneeName());

        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("status", updated.status());
        afterMap.put("assigneeName", updated.assigneeName());

        auditService.log(
                admin.userId(),
                "SUPPORT_TICKET_TRANSITION",
                "SUPPORT_TICKET",
                ticketId.toString(),
                beforeMap,
                afterMap,
                null);

        if (!before.status().equals(updated.status())) {
            safeNotify(
                    updated.ownerId(),
                    NotificationService.Type.SUPPORT_STATUS_CHANGED,
                    "SUPPORT_TICKET",
                    ticketId,
                    "Yêu cầu hỗ trợ chuyển sang trạng thái " + updated.status());
        }

        List<SupportMessage> messages = loadMessages(updated.id(), updated.ownerId(), true);
        return toTicketDetail(updated, messages);
    }

    /** Ghi thông báo ở transaction độc lập (REQUIRES_NEW) để lỗi phụ không làm rollback kết quả đã lưu (EX-04). */
    private void safeNotify(
            UUID recipientId,
            NotificationService.Type type,
            String objectType,
            UUID objectId,
            String summary) {
        try {
            requiresNewTx.executeWithoutResult(status ->
                    notificationService.notify(recipientId, type, objectType, objectId, summary));
        } catch (RuntimeException ex) {
            log.warn("Notification side effect failed for recipient={} type={}", recipientId, type, ex);
        }
    }

    private SupportMessage insertMessage(
            UUID ticketId,
            UUID authorId,
            String authorName,
            boolean fromStaff,
            MessageVisibility visibility,
            String body,
            List<UUID> fileIds) {

        MessageInsertRow inserted = jdbc.sql("""
                INSERT INTO support_message(ticket_id, author_id, visibility, body)
                VALUES (:ticketId, :authorId, :visibility, :body)
                RETURNING id, created_at
                """)
                .param("ticketId", ticketId)
                .param("authorId", authorId)
                .param("visibility", visibility.getValue())
                .param("body", body)
                .query((rs, rowNum) -> new MessageInsertRow(
                        rs.getObject("id", UUID.class),
                        TimeHelper.getOffsetDateTime(rs, "created_at")))
                .single();

        List<StoredFile> attachments = Collections.emptyList();
        if (!fileIds.isEmpty()) {
            for (UUID fid : fileIds) {
                jdbc.sql("INSERT INTO support_message_file(message_id, file_id) VALUES (:mid, :fid)")
                        .param("mid", inserted.id())
                        .param("fid", fid)
                        .update();
            }
            Map<UUID, StoredFile> fileMap = storedFileLookup.getMetadata(fileIds);
            attachments = fileIds.stream().map(fileMap::get).filter(java.util.Objects::nonNull).toList();
        }

        return new SupportMessage(inserted.id(), authorName, visibility, body, inserted.createdAt())
                .fromStaff(fromStaff)
                .attachments(attachments);
    }

    private List<SupportMessage> loadMessages(UUID ticketId, UUID ownerId, boolean includeInternal) {
        List<MessageRow> rows = jdbc.sql("""
                SELECT sm.id, sm.author_id, u.display_name AS author_name, sm.visibility, sm.body, sm.created_at
                FROM support_message sm
                JOIN app_user u ON u.id = sm.author_id
                WHERE sm.ticket_id = :ticketId
                  AND (:includeInternal OR sm.visibility = 'PUBLIC')
                ORDER BY sm.created_at ASC
                """)
                .param("ticketId", ticketId)
                .param("includeInternal", includeInternal)
                .query((rs, rowNum) -> new MessageRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("author_name"),
                        rs.getString("visibility"),
                        rs.getString("body"),
                        TimeHelper.getOffsetDateTime(rs, "created_at")))
                .list();

        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> messageIds = rows.stream().map(MessageRow::id).toList();
        List<MessageFileLink> links = jdbc.sql("""
                SELECT message_id, file_id
                FROM support_message_file
                WHERE message_id IN (:mids)
                """)
                .param("mids", messageIds)
                .query((rs, rowNum) -> new MessageFileLink(
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("file_id", UUID.class)))
                .list();

        Set<UUID> allFileIds = links.stream().map(MessageFileLink::fileId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, StoredFile> filesById = storedFileLookup.getMetadata(allFileIds);

        Map<UUID, List<StoredFile>> attachmentsByMessage = new LinkedHashMap<>();
        for (MessageFileLink link : links) {
            StoredFile sf = filesById.get(link.fileId());
            if (sf != null) {
                attachmentsByMessage.computeIfAbsent(link.messageId(), k -> new ArrayList<>()).add(sf);
            }
        }

        return rows.stream()
                .map(r -> new SupportMessage(
                        r.id(),
                        r.authorName(),
                        MessageVisibility.fromValue(r.visibility()),
                        r.body(),
                        r.createdAt())
                        .fromStaff(!ownerId.equals(r.authorId()))
                        .attachments(attachmentsByMessage.getOrDefault(r.id(), Collections.emptyList())))
                .toList();
    }

    private Optional<TicketRow> findTicketById(UUID ticketId) {
        return jdbc.sql("""
                SELECT st.id, st.owner_id, st.category, st.subject, st.status,
                       assignee.display_name AS assignee_name, st.created_at, st.updated_at
                FROM support_ticket st
                LEFT JOIN app_user assignee ON assignee.id = st.assignee_id
                WHERE st.id = :ticketId
                """)
                .param("ticketId", ticketId)
                .query((rs, rowNum) -> mapTicketRow(rs))
                .optional();
    }

    private TicketRow lockTicketById(UUID ticketId) {
        return jdbc.sql("""
                SELECT st.id, st.owner_id, st.category, st.subject, st.status,
                       assignee.display_name AS assignee_name, st.created_at, st.updated_at
                FROM support_ticket st
                LEFT JOIN app_user assignee ON assignee.id = st.assignee_id
                WHERE st.id = :ticketId
                FOR UPDATE OF st
                """)
                .param("ticketId", ticketId)
                .query((rs, rowNum) -> mapTicketRow(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ"));
    }

    private SupportTicket toTicketSummary(TicketRow row) {
        return new SupportTicket(
                row.id(),
                row.category(),
                row.subject(),
                TicketStatus.fromValue(row.status()),
                row.createdAt(),
                row.updatedAt())
                .assigneeName(row.assigneeName());
    }

    private SupportTicketDetail toTicketDetail(TicketRow row, List<SupportMessage> messages) {
        return new SupportTicketDetail(
                row.id(),
                row.category(),
                row.subject(),
                TicketStatus.fromValue(row.status()),
                row.createdAt(),
                row.updatedAt(),
                messages)
                .assigneeName(row.assigneeName());
    }

    private List<UUID> normalizeFileIds(@Nullable List<UUID> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<UUID> unique = new ArrayList<>(new LinkedHashSet<>(raw));
        if (unique.size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tối đa 5 tệp đính kèm mỗi tin nhắn");
        }
        return unique;
    }

    private TicketRow mapTicketRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TicketRow(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("category"),
                rs.getString("subject"),
                rs.getString("status"),
                rs.getString("assignee_name"),
                TimeHelper.getOffsetDateTime(rs, "created_at"),
                TimeHelper.getOffsetDateTime(rs, "updated_at"));
    }

    private record TicketRow(
            UUID id,
            UUID ownerId,
            String category,
            String subject,
            String status,
            @Nullable String assigneeName,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private record MessageInsertRow(UUID id, OffsetDateTime createdAt) {}

    private record MessageRow(
            UUID id,
            UUID authorId,
            String authorName,
            String visibility,
            String body,
            OffsetDateTime createdAt) {}

    private record MessageFileLink(UUID messageId, UUID fileId) {}
}
