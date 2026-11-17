package vn.gowithbae.report;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.AdminTransitionPlaceReportRequest;
import vn.gowithbae.api.model.PlaceReport;
import vn.gowithbae.api.model.PlaceReportCreate;
import vn.gowithbae.api.model.PlaceReportPage;
import vn.gowithbae.api.model.PlaceReportReason;
import vn.gowithbae.api.model.PlaceReportReceipt;
import vn.gowithbae.api.model.PlaceReportStatus;
import vn.gowithbae.api.model.StoredFile;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.UserPrincipal;
import vn.gowithbae.common.PageUtil;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.place.PlaceLookupService;
import vn.gowithbae.platform.PlaceQueryService;
import vn.gowithbae.platform.RequestRateLimiter;
import vn.gowithbae.shared.StoredFileLookup;

/**
 * Nghiệp vụ gửi báo sai địa điểm (kiểm tra công khai qua PlaceQueryService của B, giới hạn tần suất qua RequestRateLimiter của A)
 * và xử lý báo sai phía Admin có ghi AuditService (FR-19, FR-28, RULE-18).
 */
@Service
public class PlaceReportService {

    private static final int MAX_REPORTS_PER_HOUR = 5;

    private final JdbcClient jdbc;
    private final ActorResolver actorResolver;
    private final PlaceQueryService placeQueryService;
    private final StoredFileLookup storedFileLookup;
    private final AuditService auditService;
    private final RequestRateLimiter rateLimiter;
    private final PlaceLookupService placeLookup;

    public PlaceReportService(
            JdbcClient jdbc,
            ActorResolver actorResolver,
            PlaceQueryService placeQueryService,
            StoredFileLookup storedFileLookup,
            AuditService auditService,
            RequestRateLimiter rateLimiter,
            PlaceLookupService placeLookup) {
        this.placeLookup = placeLookup;
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.placeQueryService = placeQueryService;
        this.storedFileLookup = storedFileLookup;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public PlaceReportReceipt submitReport(UUID placeId, PlaceReportCreate req) {
        placeQueryService.requirePublished(placeId);

        Optional<UserPrincipal> userOpt = actorResolver.findAuthenticatedUser();
        UUID senderUserId = userOpt.map(UserPrincipal::userId).orElse(null);

        String peerKey = actorResolver.resolveClientPeerKey();
        if (!rateLimiter.consume("place-report-peer", peerKey, MAX_REPORTS_PER_HOUR, Duration.ofHours(1))
                || (senderUserId != null && !rateLimiter.consume(
                        "place-report-user", senderUserId.toString(), MAX_REPORTS_PER_HOUR, Duration.ofHours(1)))) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã gửi quá nhiều báo sai trong thời gian ngắn, vui lòng thử lại sau 1 giờ");
        }

        storedFileLookup.validatePlaceReportFile(senderUserId, req.getFileId());

        String description = req.getDescription() == null || req.getDescription().isBlank()
                ? null
                : req.getDescription().trim();
        String fingerprint = actorResolver.resolveClientFingerprint();

        PlaceReportReceiptRow inserted = jdbc.sql("""
                INSERT INTO place_report(place_id, reason, description, file_id, sender_user_id, sender_fingerprint, status)
                VALUES (:placeId, :reason, CAST(:desc AS text), CAST(:fileId AS uuid),
                        CAST(:senderUserId AS uuid), CAST(:fp AS text), 'NEW')
                RETURNING id, status, created_at
                """)
                .param("placeId", placeId)
                .param("reason", req.getReason().getValue())
                .param("desc", description)
                .param("fileId", req.getFileId())
                .param("senderUserId", senderUserId)
                .param("fp", fingerprint)
                .query((rs, rowNum) -> new PlaceReportReceiptRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        TimeHelper.getOffsetDateTime(rs, "created_at")))
                .single();

        return new PlaceReportReceipt(
                inserted.id(),
                PlaceReportStatus.fromValue(inserted.status()),
                inserted.createdAt());
    }

    public PlaceReportPage adminListReports(
            @Nullable PlaceReportStatus status,
            @Nullable UUID assigneeId,
            @Nullable Integer page,
            @Nullable Integer size) {

        int sanitizedPage = PageUtil.sanitizePage(page);
        int sanitizedSize = PageUtil.sanitizeSize(size);
        int offset = sanitizedPage * sanitizedSize;
        String statusStr = status == null ? null : status.getValue();

        Long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM place_report pr
                WHERE (CAST(:status AS text) IS NULL OR pr.status = CAST(:status AS text))
                  AND (CAST(:assigneeId AS uuid) IS NULL OR pr.assignee_id = CAST(:assigneeId AS uuid))
                """)
                .param("status", statusStr)
                .param("assigneeId", assigneeId)
                .query(Long.class)
                .single();

        List<ReportRow> rows = jdbc.sql("""
                SELECT pr.id, pr.place_id, CAST(NULL AS text) AS place_name, pr.reason, pr.description, pr.file_id,
                       pr.sender_user_id, pr.assignee_id, pr.status, pr.resolution_note, pr.created_at, pr.updated_at
                FROM place_report pr
                WHERE (CAST(:status AS text) IS NULL OR pr.status = CAST(:status AS text))
                  AND (CAST(:assigneeId AS uuid) IS NULL OR pr.assignee_id = CAST(:assigneeId AS uuid))
                ORDER BY pr.created_at DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("status", statusStr)
                .param("assigneeId", assigneeId)
                .param("limit", sanitizedSize)
                .param("offset", offset)
                .query((rs, rowNum) -> mapReportRow(rs))
                .list();

        Set<UUID> fileIds = rows.stream()
                .map(ReportRow::fileId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, StoredFile> files = storedFileLookup.getMetadata(fileIds);

        Map<UUID, String> names = placeLookup.names(rows.stream().map(ReportRow::placeId).toList());
        List<PlaceReport> items = rows.stream()
                .map(r -> toDto(r.withPlaceName(names.get(r.placeId())), r.fileId() == null ? null : files.get(r.fileId())))
                .toList();

        return new PlaceReportPage(items, PageUtil.buildMeta(sanitizedPage, sanitizedSize, total == null ? 0L : total));
    }

    public PlaceReport adminGetReport(UUID reportId) {
        ReportRow row = findReportRow(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy báo sai"));
        StoredFile attachment = row.fileId() == null ? null : storedFileLookup.getMetadata(List.of(row.fileId())).get(row.fileId());
        return toDto(withName(row), attachment);
    }

    @Transactional
    public PlaceReport adminTransitionReport(UUID reportId, AdminTransitionPlaceReportRequest req) {
        UserPrincipal admin = actorResolver.requireActiveUser();

        ReportRow before = jdbc.sql("""
                SELECT pr.id, pr.place_id, CAST(NULL AS text) AS place_name, pr.reason, pr.description, pr.file_id,
                       pr.sender_user_id, pr.assignee_id, pr.status, pr.resolution_note, pr.created_at, pr.updated_at
                FROM place_report pr
                WHERE pr.id = :id
                FOR UPDATE OF pr
                """)
                .param("id", reportId)
                .query((rs, rowNum) -> mapReportRow(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy báo sai"));

        PlaceReportStatus current = PlaceReportStatus.fromValue(before.status());
        PlaceReportStatus target = req.getStatus();
        validateTransition(current, target);

        String note = req.getNote() == null || req.getNote().isBlank() ? null : req.getNote().trim();
        if ((target == PlaceReportStatus.RESOLVED || target == PlaceReportStatus.INSUFFICIENT) && note == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đóng báo sai bắt buộc phải có ghi chú xử lý");
        }

        if (req.getAssigneeId() != null) {
            Boolean assigneeExists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :uid)")
                    .param("uid", req.getAssigneeId())
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(assigneeExists)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Người phụ trách không tồn tại");
            }
        }

        ReportRow updated = jdbc.sql("""
                UPDATE place_report
                SET status = :status,
                    assignee_id = COALESCE(CAST(:assigneeId AS uuid), assignee_id),
                    resolution_note = COALESCE(CAST(:note AS text), resolution_note),
                    updated_at = now()
                WHERE id = :id
                RETURNING id, place_id, reason, description, file_id,
                          sender_user_id, assignee_id, status, resolution_note, created_at, updated_at
                """)
                .param("status", target.getValue())
                .param("assigneeId", req.getAssigneeId())
                .param("note", note)
                .param("id", reportId)
                .query((rs, rowNum) -> new ReportRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("place_id", UUID.class),
                        null,
                        rs.getString("reason"),
                        rs.getString("description"),
                        rs.getObject("file_id", UUID.class),
                        rs.getObject("sender_user_id", UUID.class),
                        rs.getObject("assignee_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("resolution_note"),
                        TimeHelper.getOffsetDateTime(rs, "created_at"),
                        TimeHelper.getOffsetDateTime(rs, "updated_at")))
                .single();

        Map<String, Object> beforeAudit = new LinkedHashMap<>();
        beforeAudit.put("status", before.status());
        beforeAudit.put("assigneeId", before.assigneeId());

        Map<String, Object> afterAudit = new LinkedHashMap<>();
        afterAudit.put("status", updated.status());
        afterAudit.put("assigneeId", updated.assigneeId());

        auditService.log(
                admin.userId(),
                "PLACE_REPORT_TRANSITION",
                "PLACE_REPORT",
                reportId.toString(),
                beforeAudit,
                afterAudit,
                note);

        StoredFile attachment = updated.fileId() == null ? null
                : storedFileLookup.getMetadata(List.of(updated.fileId())).get(updated.fileId());
        return toDto(withName(updated), attachment);
    }

    private void validateTransition(PlaceReportStatus current, PlaceReportStatus target) {
        boolean allowed = switch (current) {
            case NEW -> target == PlaceReportStatus.NEW
                    || target == PlaceReportStatus.CHECKING
                    || target == PlaceReportStatus.RESOLVED
                    || target == PlaceReportStatus.INSUFFICIENT;
            case CHECKING -> target == PlaceReportStatus.CHECKING
                    || target == PlaceReportStatus.RESOLVED
                    || target == PlaceReportStatus.INSUFFICIENT;
            case RESOLVED, INSUFFICIENT -> target == PlaceReportStatus.CHECKING;
        };
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Không thể chuyển trạng thái báo sai từ " + current.getValue() + " sang " + target.getValue());
        }
    }

    private Optional<ReportRow> findReportRow(UUID reportId) {
        return jdbc.sql("""
                SELECT pr.id, pr.place_id, CAST(NULL AS text) AS place_name, pr.reason, pr.description, pr.file_id,
                       pr.sender_user_id, pr.assignee_id, pr.status, pr.resolution_note, pr.created_at, pr.updated_at
                FROM place_report pr
                WHERE pr.id = :id
                """)
                .param("id", reportId)
                .query((rs, rowNum) -> mapReportRow(rs))
                .optional();
    }

    /** Tên địa điểm lấy qua PlaceLookupService của phần B, không JOIN thẳng bảng place. */
    private ReportRow withName(ReportRow row) {
        return row.withPlaceName(placeLookup.names(List.of(row.placeId())).get(row.placeId()));
    }

    private PlaceReport toDto(ReportRow row, @Nullable StoredFile attachment) {
        return new PlaceReport(
                row.id(),
                row.placeId(),
                row.placeName(),
                PlaceReportReason.fromValue(row.reason()),
                PlaceReportStatus.fromValue(row.status()),
                row.createdAt())
                .description(row.description())
                .attachment(attachment)
                .senderUserId(row.senderUserId())
                .assigneeId(row.assigneeId())
                .resolutionNote(row.resolutionNote())
                .updatedAt(row.updatedAt());
    }

    private ReportRow mapReportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReportRow(
                rs.getObject("id", UUID.class),
                rs.getObject("place_id", UUID.class),
                rs.getString("place_name"),
                rs.getString("reason"),
                rs.getString("description"),
                rs.getObject("file_id", UUID.class),
                rs.getObject("sender_user_id", UUID.class),
                rs.getObject("assignee_id", UUID.class),
                rs.getString("status"),
                rs.getString("resolution_note"),
                TimeHelper.getOffsetDateTime(rs, "created_at"),
                TimeHelper.getOffsetDateTime(rs, "updated_at"));
    }

    private record PlaceReportReceiptRow(UUID id, String status, OffsetDateTime createdAt) {}

    private record ReportRow(
            UUID id,
            UUID placeId,
            String placeName,
            String reason,
            @Nullable String description,
            @Nullable UUID fileId,
            @Nullable UUID senderUserId,
            @Nullable UUID assigneeId,
            String status,
            @Nullable String resolutionNote,
            OffsetDateTime createdAt,
            @Nullable OffsetDateTime updatedAt) {

        ReportRow withPlaceName(String name) {
            return new ReportRow(id, placeId, name, reason, description, fileId, senderUserId, assigneeId, status,
                    resolutionNote, createdAt, updatedAt);
        }
    }
}
