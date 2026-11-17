package vn.gowithbae.admin.place;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.gowithbae.api.model.AdminPlace;
import vn.gowithbae.api.model.AdminPlacePage;
import vn.gowithbae.api.model.AdminPlaceWrite;
import vn.gowithbae.api.model.AdminPlaceWritePrice;
import vn.gowithbae.api.model.AdminTransitionPlaceRequest.ActionEnum;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.OpeningHours;
import vn.gowithbae.api.model.OpeningHoursExceptionsInner;
import vn.gowithbae.api.model.OpeningHoursWeeklyInner;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.PlaceDetail;
import vn.gowithbae.api.model.PlaceImage;
import vn.gowithbae.api.model.PlaceImageWrite;
import vn.gowithbae.api.model.PlaceStatus;
import vn.gowithbae.api.model.TimeRange;
import vn.gowithbae.common.audit.AuditService;
import vn.gowithbae.common.files.FileUrls;
import vn.gowithbae.common.security.CurrentUser;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.place.PlaceReader;
import vn.gowithbae.place.PlacesDelegate;
import vn.gowithbae.place.SearchTextUpdater;

/**
 * Biên tập, duyệt, ẩn địa điểm (FR-23, FR-24, RULE-20..22, RULE-28). Quyền theo vai trò kiểm tra ở delegate;
 * quy tắc phụ thuộc dữ liệu (tự duyệt, trạng thái được sửa) kiểm tra ở đây.
 */
@Service
public class AdminPlaceService {

    /** shortcut: OQ-15 — chỉ sửa nội dung khi DRAFT/PENDING; bản công khai/ẩn phải RESTORE hoặc chờ bảng phiên bản. */
    private static final Set<String> EDITABLE = Set.of("DRAFT", "PENDING");

    /** Vòng đời §7.2: hành động → trạng thái nguồn hợp lệ và trạng thái đích. */
    private record Transition(Set<String> from, String to, boolean needsReason, boolean checksBlockers) {
    }

    private static final Map<ActionEnum, Transition> TRANSITIONS = Map.of(
            ActionEnum.SUBMIT, new Transition(Set.of("DRAFT"), "PENDING", false, true),
            ActionEnum.APPROVE, new Transition(Set.of("PENDING"), "PUBLISHED", false, true),
            ActionEnum.RETURN, new Transition(Set.of("PENDING"), "DRAFT", true, false),
            ActionEnum.HIDE, new Transition(Set.of("PUBLISHED"), "HIDDEN", true, false),
            ActionEnum.DEACTIVATE, new Transition(Set.of("PUBLISHED"), "INACTIVE", true, false),
            ActionEnum.RESTORE, new Transition(Set.of("HIDDEN", "INACTIVE"), "PENDING", false, false));

    /** shortcut: ngưỡng nghi trùng (cùng tên chuẩn hóa trong bán kính này) là đề xuất, chưa có trong SRS. */
    private static final double DUPLICATE_RADIUS_KM = 0.1;

    private final JdbcClient jdbc;
    private final PlaceReader reader;
    private final SearchTextUpdater searchText;
    private final AuditService audit;
    private final FileUrls fileUrls;

    public AdminPlaceService(JdbcClient jdbc, PlaceReader reader, SearchTextUpdater searchText, AuditService audit,
            FileUrls fileUrls) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.searchText = searchText;
        this.audit = audit;
        this.fileUrls = fileUrls;
    }

    // ───────────── Đọc ─────────────

    @Transactional(readOnly = true)
    public AdminPlacePage list(PlaceStatus status, UUID areaId, String q, int page, int size) {
        var where = new StringBuilder("TRUE");
        var params = new HashMap<String, Object>();
        if (status != null) {
            where.append(" AND p.status = :status");
            params.put("status", status.getValue());
        }
        if (areaId != null) {
            where.append(" AND p.area_id = :areaId");
            params.put("areaId", areaId);
        }
        String needle = PlacesDelegate.normalize(q);
        if (!needle.isEmpty()) {
            where.append(" AND (p.search_text LIKE :q ESCAPE '\\' OR lower(p.code) LIKE :q ESCAPE '\\')");
            params.put("q", "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        long total = jdbc.sql("SELECT count(*) FROM place p WHERE " + where).params(params).query(Long.class).single();
        List<UUID> ids = jdbc.sql("SELECT p.id FROM place p WHERE " + where
                        + " ORDER BY p.updated_at DESC, p.code LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(UUID.class)
                .list();
        return new AdminPlacePage(toAdmin(ids), new PageMeta(page, size, total, (int) ((total + size - 1) / size)));
    }

    @Transactional(readOnly = true)
    public AdminPlace get(UUID id) {
        List<AdminPlace> found = toAdmin(List.of(id));
        if (found.isEmpty()) {
            throw Problems.notFound();
        }
        return found.getFirst();
    }

    // ───────────── Ghi nháp ─────────────

    /** Tạo nháp; đủ điều kiện xuất bản chỉ kiểm tra khi SUBMIT/APPROVE (RULE-20). */
    @Transactional
    public AdminPlace create(AdminPlaceWrite w) {
        return get(createDraft(w, null));
    }

    /**
     * Tạo nháp, trả id (không dựng lại hồ sơ). Dùng chung cho tạo tay và nhập CSV;
     * {@code auditReason} ghi nguồn tạo, ví dụ lô CSV.
     */
    @Transactional
    public UUID createDraft(AdminPlaceWrite w, String auditReason) {
        validate(w, null);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO place (code, name, category_id, area_id, address, lat, lng, description, phone, website,
                                       notes, reservation_recommended, hours_status, status, source, verified_at, created_by)
                    VALUES (:code, :name, :categoryId, :areaId, :address, :lat, :lng, :description, :phone, :website,
                            :notes, :reservation, :hoursStatus, 'DRAFT', :source, :verifiedAt, :actor)
                    RETURNING id
                    """)
                    .params(placeParams(w))
                    .param("actor", CurrentUser.id())
                    .query(UUID.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw codeTaken(); // hai request đồng thời cùng mã: ràng buộc unique của DB là chốt chặn cuối
        }
        writeChildren(id, w);
        searchText.refresh(List.of(id));
        audit.log(CurrentUser.id(), "PLACE_CREATE", "place", id, null, snapshot(id), auditReason);
        return id;
    }

    @Transactional
    public AdminPlace update(UUID id, AdminPlaceWrite w) {
        String status = lockStatus(id);
        requireEditable(status);
        validate(w, id);
        Map<String, Object> before = snapshot(id);
        try {
            jdbc.sql("""
                    UPDATE place SET code = :code, name = :name, category_id = :categoryId, area_id = :areaId,
                           address = :address, lat = :lat, lng = :lng, description = :description, phone = :phone,
                           website = :website, notes = :notes, reservation_recommended = :reservation,
                           hours_status = :hoursStatus, source = :source, verified_at = :verifiedAt, updated_at = now()
                    WHERE id = :id
                    """)
                    .params(placeParams(w))
                    .param("id", id)
                    .update();
        } catch (DuplicateKeyException e) {
            throw codeTaken();
        }
        writeChildren(id, w);
        searchText.refresh(List.of(id));
        audit.log(CurrentUser.id(), "PLACE_UPDATE", "place", id, before, snapshot(id), null);
        return get(id);
    }

    // ───────────── Vòng đời ─────────────

    /**
     * Quyền theo vai trò đã kiểm tra ở delegate. Ở đây: trạng thái nguồn, lý do bắt buộc, điều kiện xuất bản,
     * không tự duyệt nơi mình tạo (RULE-21; vai trò kép OQ-18 — mặc định cấm cả SUPER_ADMIN).
     */
    @Transactional
    public AdminPlace transition(UUID id, ActionEnum action, String reason) {
        var row = jdbc.sql("SELECT status, created_by FROM place WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, i) -> new String[] { rs.getString("status"), rs.getString("created_by") })
                .optional()
                .orElseThrow(Problems::notFound);
        String from = row[0];
        Transition t = TRANSITIONS.get(action);
        if (!t.from().contains(from)) {
            throw Problems.conflict("INVALID_TRANSITION",
                    "Không thể " + action.getValue() + " địa điểm đang ở trạng thái " + from);
        }
        String cleanReason = blankToNull(reason);
        if (t.needsReason() && cleanReason == null) {
            throw Problems.badRequest("Cần ghi lý do", List.of(Problems.fieldError("reason", "Bắt buộc với " + action.getValue())));
        }
        UUID actor = CurrentUser.id();
        if (action == ActionEnum.APPROVE && actor.toString().equals(row[1])) {
            throw Problems.forbidden("Không được tự duyệt địa điểm do chính mình tạo");
        }
        if (t.checksBlockers()) {
            List<String> blockers = publishBlockers(id);
            if (!blockers.isEmpty()) {
                throw Problems.badRequest("Chưa đủ điều kiện xuất bản",
                        blockers.stream().map(b -> Problems.fieldError("publishBlockers", b)).toList());
            }
        }
        jdbc.sql("""
                UPDATE place SET status = :to, status_reason = :reason, updated_at = now(),
                       reviewed_by = CASE WHEN :approve THEN CAST(:actor AS uuid) ELSE reviewed_by END,
                       published_at = CASE WHEN :approve THEN now() ELSE published_at END
                WHERE id = :id
                """)
                .param("to", t.to())
                .param("reason", cleanReason)
                .param("approve", action == ActionEnum.APPROVE)
                .param("actor", actor)
                .param("id", id)
                .update();
        audit.log(actor, "PLACE_" + action.getValue(), "place", id,
                Map.of("status", from), Map.of("status", t.to()), cleanReason);
        return get(id);
    }

    // ───────────── Ảnh ─────────────

    /** Chỉ ảnh đã tải lên với mục đích PLACE_IMAGE và có ghi nguồn/quyền sử dụng (RULE-28). */
    @Transactional
    public PlaceImage addImage(UUID placeId, PlaceImageWrite w) {
        requireEditable(lockStatus(placeId));
        String rights = blankToNull(w.getSourceRights());
        if (rights == null) {
            throw Problems.badRequest("Thiếu nguồn ảnh", List.of(Problems.fieldError("sourceRights", "Bắt buộc")));
        }
        String storageKey = jdbc.sql("SELECT storage_key FROM stored_file WHERE id = :id AND purpose = 'PLACE_IMAGE'")
                .param("id", w.getFileId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> Problems.badRequest("Tệp không hợp lệ",
                        List.of(Problems.fieldError("fileId", "Không tìm thấy ảnh đã tải lên cho địa điểm"))));
        boolean cover = Boolean.TRUE.equals(w.getCover());
        if (cover) {
            jdbc.sql("UPDATE place_image SET is_cover = false WHERE place_id = :p AND is_cover").param("p", placeId).update();
        }
        int sortOrder = w.getSortOrder() == null ? 0 : w.getSortOrder();
        UUID imageId = jdbc.sql("""
                INSERT INTO place_image (place_id, file_id, sort_order, is_cover, caption, source_rights)
                VALUES (:p, :f, :sort, :cover, :caption, :rights) RETURNING id
                """)
                .param("p", placeId)
                .param("f", w.getFileId())
                .param("sort", sortOrder)
                .param("cover", cover)
                .param("caption", blankToNull(w.getCaption()))
                .param("rights", rights)
                .query(UUID.class)
                .single();
        touch(placeId);
        audit.log(CurrentUser.id(), "PLACE_IMAGE_ADD", "place", placeId, null,
                Map.of("imageId", imageId.toString(), "fileId", w.getFileId().toString(), "cover", cover), null);
        // shortcut: OQ-21 — chưa cấu hình URL gốc thì url null dù contract yêu cầu; FE quản trị hiển thị ảnh mặc định.
        return new PlaceImage(imageId, fileUrls.publicUrl(storageKey), sortOrder, cover).caption(blankToNull(w.getCaption()));
    }

    @Transactional
    public void removeImage(UUID placeId, UUID imageId) {
        requireEditable(lockStatus(placeId));
        int deleted = jdbc.sql("DELETE FROM place_image WHERE id = :i AND place_id = :p")
                .param("i", imageId).param("p", placeId).update();
        if (deleted == 0) {
            throw Problems.notFound();
        }
        touch(placeId);
        audit.log(CurrentUser.id(), "PLACE_IMAGE_REMOVE", "place", placeId, Map.of("imageId", imageId.toString()), null, null);
    }

    // ───────────── Điều kiện xuất bản (RULE-20) ─────────────

    /**
     * Danh sách lỗi còn thiếu để xuất bản; rỗng = đủ điều kiện. Giá/giờ "chưa rõ" vẫn hợp lệ nếu đã ghi trạng thái.
     * shortcut: hours_status luôn có giá trị (mặc định UNKNOWN) nên chưa phân biệt "chưa nhập" với "đã ghi chưa rõ".
     */
    public List<String> publishBlockers(UUID id) {
        var row = jdbc.sql("""
                SELECT p.name, p.description, p.source, p.verified_at, p.lat, p.lng,
                       c.status AS category_status, a.status AS area_status,
                       EXISTS (SELECT 1 FROM place_price pp WHERE pp.place_id = p.id) AS has_price,
                       (SELECT count(*) FROM place_image i WHERE i.place_id = p.id) AS images,
                       EXISTS (SELECT 1 FROM place_image i WHERE i.place_id = p.id AND i.is_cover) AS has_cover,
                       EXISTS (SELECT 1 FROM place_image i WHERE i.place_id = p.id AND btrim(i.source_rights) = '') AS image_without_rights,
                       EXISTS (SELECT 1 FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
                               WHERE pt.place_id = p.id AND t.group_code = 'ACTIVITY' AND t.status = 'ACTIVE') AS has_activity
                FROM place p
                LEFT JOIN taxonomy c ON c.id = p.category_id
                LEFT JOIN area a ON a.id = p.area_id
                WHERE p.id = :id
                """)
                .param("id", id)
                .query((rs, i) -> {
                    List<String> b = new ArrayList<>();
                    if (blankToNull(rs.getString("description")) == null) {
                        b.add("Thiếu mô tả");
                    }
                    if (!"ACTIVE".equals(rs.getString("category_status"))) {
                        b.add("Loại hình đã ngừng dùng");
                    }
                    if (!"ACTIVE".equals(rs.getString("area_status"))) {
                        b.add("Khu vực đã ngừng dùng");
                    }
                    if (rs.getLong("images") == 0) {
                        b.add("Chưa có ảnh có quyền sử dụng");
                    } else if (!rs.getBoolean("has_cover")) {
                        b.add("Chưa chọn ảnh bìa");
                    }
                    if (rs.getBoolean("image_without_rights")) {
                        b.add("Có ảnh thiếu nguồn/quyền sử dụng");
                    }
                    if (!rs.getBoolean("has_activity")) {
                        b.add("Chưa có hoạt động chính");
                    }
                    if (!rs.getBoolean("has_price")) {
                        b.add("Chưa ghi trạng thái giá (đã biết hoặc chưa rõ)");
                    }
                    if (blankToNull(rs.getString("source")) == null || rs.getObject("verified_at") == null) {
                        b.add("Thiếu nguồn dữ liệu hoặc ngày kiểm tra");
                    }
                    duplicateOf(id, rs.getString("name"), rs.getDouble("lat"), rs.getDouble("lng"))
                            .forEach(code -> b.add("Có thể trùng với địa điểm " + code));
                    return b;
                })
                .optional()
                .orElseThrow(Problems::notFound);
        return row;
    }

    /** Địa điểm khác đang chờ duyệt/công khai, cùng tên chuẩn hóa, trong bán kính nghi trùng. */
    private List<String> duplicateOf(UUID id, String name, double lat, double lng) {
        String normalized = PlacesDelegate.normalize(name);
        List<String> codes = new ArrayList<>();
        jdbc.sql("SELECT p.code, p.name FROM place p WHERE p.id <> :id AND p.status IN ('PENDING', 'PUBLISHED') AND "
                        + PlaceReader.DISTANCE_KM + " <= :radius ORDER BY p.code")
                .param("id", id)
                .param("originLat", lat)
                .param("originLng", lng)
                .param("radius", DUPLICATE_RADIUS_KM)
                .query(rs -> {
                    if (PlacesDelegate.normalize(rs.getString("name")).equals(normalized)) {
                        codes.add(rs.getString("code"));
                    }
                });
        return codes;
    }

    // ───────────── Nội bộ ─────────────

    private List<AdminPlace> toAdmin(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Map<String, Object>> meta = new HashMap<>();
        jdbc.sql("""
                SELECT id, status, status_reason, source, created_by, reviewed_by, created_at, updated_at
                FROM place WHERE id IN (:ids)
                """)
                .param("ids", ids)
                .query(rs -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("statusReason", rs.getString("status_reason"));
                    m.put("source", rs.getString("source"));
                    m.put("createdBy", rs.getObject("created_by", UUID.class));
                    m.put("reviewedBy", rs.getObject("reviewed_by", UUID.class));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
                    meta.put(rs.getObject("id", UUID.class), m);
                });
        List<AdminPlace> out = new ArrayList<>();
        for (PlaceDetail d : reader.details(ids, null, null, false)) {
            Map<String, Object> m = meta.get(d.getId());
            out.add(new AdminPlace(d.getId(), d.getCode(), d.getName(), d.getImages(), d.getPrice(), d.getTags(),
                    d.getHours(), PlaceStatus.fromValue((String) m.get("status")))
                    .category(d.getCategory())
                    .area(d.getArea())
                    .description(d.getDescription())
                    .address(d.getAddress())
                    .lat(d.getLat())
                    .lng(d.getLng())
                    .phone(d.getPhone())
                    .website(d.getWebsite())
                    .notes(d.getNotes())
                    .reservationRecommended(d.getReservationRecommended())
                    .verifiedAt(d.getVerifiedAt())
                    .statusReason((String) m.get("statusReason"))
                    .source((String) m.get("source"))
                    .createdBy((UUID) m.get("createdBy"))
                    .reviewedBy((UUID) m.get("reviewedBy"))
                    .createdAt((OffsetDateTime) m.get("createdAt"))
                    .updatedAt((OffsetDateTime) m.get("updatedAt"))
                    .publishBlockers(publishBlockers(d.getId())));
        }
        return out;
    }

    /** Kiểm tra tham chiếu và dữ liệu trước khi ghi; gom mọi lỗi theo trường vào một lần 400. */
    private void validate(AdminPlaceWrite w, UUID selfId) {
        List<Map<String, String>> errors = new ArrayList<>();

        String categoryState = jdbc.sql("SELECT group_code || ':' || status FROM taxonomy WHERE id = :id")
                .param("id", w.getCategoryId()).query(String.class).optional().orElse(null);
        if (!"CATEGORY:ACTIVE".equals(categoryState)) {
            errors.add(Problems.fieldError("categoryId", "Phải là loại hình đang dùng"));
        }
        String areaStatus = jdbc.sql("SELECT status FROM area WHERE id = :id")
                .param("id", w.getAreaId()).query(String.class).optional().orElse(null);
        if (!"ACTIVE".equals(areaStatus)) {
            errors.add(Problems.fieldError("areaId", "Phải là khu vực đang dùng"));
        }

        Set<UUID> tagIds = new HashSet<>(w.getTagIds() == null ? List.of() : w.getTagIds());
        if (!tagIds.isEmpty()) {
            long valid = jdbc.sql("""
                    SELECT count(*) FROM taxonomy WHERE id IN (:ids) AND group_code <> 'CATEGORY' AND status = 'ACTIVE'
                    """).param("ids", tagIds).query(Long.class).single();
            if (valid != tagIds.size()) {
                errors.add(Problems.fieldError("tagIds", "Có nhãn không tồn tại, đã ngừng dùng hoặc là loại hình"));
            }
        }

        AdminPlaceWritePrice price = w.getPrice();
        if (price != null && price.getStatus() == KnownStatus.KNOWN) {
            Long std = price.getStandardForTwo();
            Long min = price.getMin();
            Long max = price.getMax();
            if (std == null || min == null || max == null || min < 0 || min > std || std > max) {
                errors.add(Problems.fieldError("price", "Giá đã biết cần 0 ≤ min ≤ standardForTwo ≤ max"));
            }
        }

        OpeningHours hours = w.getHours();
        if (hours != null && hours.getStatus() == KnownStatus.KNOWN) {
            for (OpeningHoursWeeklyInner day : hours.getWeekly()) {
                for (TimeRange r : day.getRanges()) {
                    if (parseTime(r.getOpensAt()) == null || parseTime(r.getClosesAt()) == null) {
                        errors.add(Problems.fieldError("hours.weekly", "Giờ không hợp lệ: " + r.getOpensAt() + "–" + r.getClosesAt()));
                    }
                }
            }
            Set<Object> dates = new HashSet<>();
            for (OpeningHoursExceptionsInner ex : hours.getExceptions()) {
                if (!dates.add(ex.getDate())) {
                    errors.add(Problems.fieldError("hours.exceptions", "Trùng ngày ngoại lệ " + ex.getDate()));
                }
                if (Boolean.FALSE.equals(ex.getClosed())) {
                    // shortcut: bảng date_exception chỉ chứa một khoảng giờ cho mỗi ngày ngoại lệ.
                    if (ex.getRanges().size() != 1) {
                        errors.add(Problems.fieldError("hours.exceptions", "Ngày ngoại lệ mở cửa cần đúng một khoảng giờ: " + ex.getDate()));
                    } else if (parseTime(ex.getRanges().getFirst().getOpensAt()) == null
                            || parseTime(ex.getRanges().getFirst().getClosesAt()) == null) {
                        errors.add(Problems.fieldError("hours.exceptions", "Giờ không hợp lệ ngày " + ex.getDate()));
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw Problems.badRequest("Dữ liệu địa điểm không hợp lệ", errors);
        }
        boolean taken = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM place WHERE code = :code AND (CAST(:self AS uuid) IS NULL OR id <> CAST(:self AS uuid)))
                """).param("code", w.getCode()).param("self", selfId).query(Boolean.class).single();
        if (taken) {
            throw codeTaken();
        }
    }

    private Map<String, Object> placeParams(AdminPlaceWrite w) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", w.getCode());
        p.put("name", w.getName().trim());
        p.put("categoryId", w.getCategoryId());
        p.put("areaId", w.getAreaId());
        p.put("address", w.getAddress().trim());
        p.put("lat", w.getLat());
        p.put("lng", w.getLng());
        p.put("description", blankToNull(w.getDescription()));
        p.put("phone", blankToNull(w.getPhone()));
        p.put("website", w.getWebsite() == null ? null : w.getWebsite().toString());
        p.put("notes", blankToNull(w.getNotes()));
        p.put("reservation", w.getReservationRecommended());
        p.put("hoursStatus", w.getHours() != null && w.getHours().getStatus() == KnownStatus.KNOWN ? "KNOWN" : "UNKNOWN");
        p.put("source", blankToNull(w.getSource()));
        p.put("verifiedAt", w.getVerifiedAt());
        return p;
    }

    /** Ghi lại toàn bộ nhãn, giá, giờ theo request (thay thế, không gộp). */
    private void writeChildren(UUID id, AdminPlaceWrite w) {
        for (String table : List.of("place_tag", "place_price", "opening_hours", "date_exception")) {
            jdbc.sql("DELETE FROM " + table + " WHERE place_id = :id").param("id", id).update();
        }
        for (UUID tagId : new HashSet<>(w.getTagIds() == null ? List.<UUID>of() : w.getTagIds())) {
            jdbc.sql("INSERT INTO place_tag (place_id, taxonomy_id) VALUES (:p, :t)").param("p", id).param("t", tagId).update();
        }
        AdminPlaceWritePrice price = w.getPrice();
        if (price != null) {
            boolean known = price.getStatus() == KnownStatus.KNOWN;
            jdbc.sql("""
                    INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price, includes, source, verified_at)
                    VALUES (:p, :status, :std, :min, :max, :includes, :source, :verifiedAt)
                    """)
                    .param("p", id)
                    .param("status", price.getStatus().getValue())
                    .param("std", known ? price.getStandardForTwo() : null)
                    .param("min", known ? price.getMin() : null)
                    .param("max", known ? price.getMax() : null)
                    .param("includes", blankToNull(price.getIncludes()))
                    .param("source", blankToNull(price.getSource()))
                    .param("verifiedAt", price.getVerifiedAt())
                    .update();
        }
        OpeningHours hours = w.getHours();
        if (hours == null || hours.getStatus() != KnownStatus.KNOWN) {
            return;
        }
        for (OpeningHoursWeeklyInner day : hours.getWeekly()) {
            for (TimeRange r : day.getRanges()) {
                jdbc.sql("INSERT INTO opening_hours (place_id, day_of_week, opens_at, closes_at) VALUES (:p, :d, :o, :c)")
                        .param("p", id).param("d", day.getDayOfWeek())
                        .param("o", parseTime(r.getOpensAt())).param("c", parseTime(r.getClosesAt()))
                        .update();
            }
        }
        for (OpeningHoursExceptionsInner ex : hours.getExceptions()) {
            boolean closed = !Boolean.FALSE.equals(ex.getClosed());
            TimeRange r = closed ? null : ex.getRanges().getFirst();
            jdbc.sql("""
                    INSERT INTO date_exception (place_id, on_date, closed, opens_at, closes_at, note)
                    VALUES (:p, :d, :closed, :o, :c, :note)
                    """)
                    .param("p", id).param("d", ex.getDate()).param("closed", closed)
                    .param("o", r == null ? null : parseTime(r.getOpensAt()))
                    .param("c", r == null ? null : parseTime(r.getClosesAt()))
                    .param("note", blankToNull(ex.getNote()))
                    .update();
        }
    }

    /** Ảnh chụp các trường quan trọng cho audit: trạng thái, tham chiếu, giá (đổi giá phải có dấu vết, RULE-21). */
    private Map<String, Object> snapshot(UUID id) {
        return jdbc.sql("""
                SELECT p.code, p.name, p.status, p.category_id, p.area_id, p.hours_status,
                       pp.status AS price_status, pp.standard_for_two, pp.min_price, pp.max_price
                FROM place p LEFT JOIN place_price pp ON pp.place_id = p.id WHERE p.id = :id
                """)
                .param("id", id)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("status", rs.getString("status"));
                    m.put("categoryId", rs.getString("category_id"));
                    m.put("areaId", rs.getString("area_id"));
                    m.put("hoursStatus", rs.getString("hours_status"));
                    m.put("priceStatus", rs.getString("price_status"));
                    m.put("standardForTwo", rs.getObject("standard_for_two", Long.class));
                    m.put("minPrice", rs.getObject("min_price", Long.class));
                    m.put("maxPrice", rs.getObject("max_price", Long.class));
                    return m;
                })
                .single();
    }

    /** Khóa dòng địa điểm trong transaction để thao tác đồng thời không ghi đè lẫn nhau. */
    private String lockStatus(UUID id) {
        return jdbc.sql("SELECT status FROM place WHERE id = :id FOR UPDATE")
                .param("id", id).query(String.class).optional().orElseThrow(Problems::notFound);
    }

    private static void requireEditable(String status) {
        if (!EDITABLE.contains(status)) {
            throw Problems.conflict("PLACE_NOT_EDITABLE",
                    "Chỉ sửa được địa điểm nháp hoặc chờ duyệt (đang " + status + "); sửa bản công khai chờ chốt OQ-15");
        }
    }

    private void touch(UUID placeId) {
        jdbc.sql("UPDATE place SET updated_at = now() WHERE id = :id").param("id", placeId).update();
    }

    private static RuntimeException codeTaken() {
        return Problems.conflict("PLACE_CODE_TAKEN", "Mã chi nhánh đã tồn tại");
    }

    private static LocalTime parseTime(String s) {
        try {
            return s == null ? null : LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
