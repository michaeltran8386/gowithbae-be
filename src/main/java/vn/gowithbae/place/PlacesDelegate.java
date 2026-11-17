package vn.gowithbae.place;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.PlacesApiDelegate;
import vn.gowithbae.api.model.BudgetBand;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.PlaceDetail;
import vn.gowithbae.api.model.PlaceReportCreate;
import vn.gowithbae.api.model.PlaceReportReceipt;
import vn.gowithbae.api.model.PlaceSearchPage;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.report.PlaceReportService;

/**
 * Tìm/lọc, chi tiết, so sánh địa điểm công khai (B: FR-02..06, RULE-01..06)
 * và gửi báo sai địa điểm (C: FR-19, RULE-18).
 */
@Service
public class PlacesDelegate implements PlacesApiDelegate {

    /** Trong nhóm: chỉ cần khớp một nhãn (RULE-02). Các nhóm còn lại (VIBE/FEATURE/AMENITY) phải khớp đủ. */
    private static final Set<TaxonomyGroup> ANY_OF_GROUPS =
            Set.of(TaxonomyGroup.ACTIVITY, TaxonomyGroup.STYLE, TaxonomyGroup.SCENERY, TaxonomyGroup.OCCASION);

    private final JdbcClient jdbc;
    private final PlaceReader reader;
    private final PlaceReportService placeReportService;

    public PlacesDelegate(JdbcClient jdbc, PlaceReader reader, PlaceReportService placeReportService) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.placeReportService = placeReportService;
    }

    @Override
    public ResponseEntity<PlaceReportReceipt> reportPlace(UUID placeId, PlaceReportCreate placeReportCreate) {
        return ResponseEntity.status(HttpStatus.CREATED).body(placeReportService.submitReport(placeId, placeReportCreate));
    }

    @Override
    public ResponseEntity<PlaceSearchPage> searchPlaces(String q, UUID areaId, Double lat, Double lng, Double radiusKm,
            BudgetBand budget, OffsetDateTime at, List<UUID> tagIds, String sort, Integer page, Integer size) {
        boolean hasOrigin = validateOrigin(lat, lng, radiusKm, sort);
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;

        var where = new StringBuilder("p.status = 'PUBLISHED'");
        var params = new HashMap<String, Object>();
        String needle = normalize(q);
        if (!needle.isEmpty()) {
            where.append(" AND p.search_text LIKE :q ESCAPE '\\'");
            params.put("q", "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (areaId != null) {
            where.append(" AND p.area_id = :areaId");
            params.put("areaId", areaId);
        }
        if (budget != null) {
            // Giá chưa biết không đạt lọc ngân sách (RULE-03/04). Ranh giới theo PROP RULE-03, chờ OQ-03.
            where.append(" AND pp.status = 'KNOWN' AND ").append(switch (budget) {
                case FREE -> "pp.standard_for_two = 0";
                case UNDER_200_K -> "pp.standard_for_two > 0 AND pp.standard_for_two < 200000";
                case FROM_200_K_TO_400_K -> "pp.standard_for_two >= 200000 AND pp.standard_for_two < 400000";
                case FROM_400_K_TO_700_K -> "pp.standard_for_two >= 400000 AND pp.standard_for_two <= 700000";
                case OVER_700_K -> "pp.standard_for_two > 700000";
            });
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            appendTagFilter(tagIds, where, params);
        }
        if (at != null) {
            appendOpenAtFilter(at, where, params);
        }
        if (hasOrigin) {
            params.put("originLat", lat);
            params.put("originLng", lng);
            if (radiusKm != null) {
                where.append(" AND ").append(PlaceReader.DISTANCE_KM).append(" <= :radiusKm");
                params.put("radiusKm", radiusKm);
            }
        }

        // shortcut: OQ-04 chưa có công thức mức khớp; DEFAULT xếp theo ngày kiểm tra mới nhất.
        String orderBy = switch (sort == null ? "DEFAULT" : sort) {
            case "PRICE_ASC" -> "pp.standard_for_two ASC NULLS LAST, p.code"; // giá chưa rõ xếp cuối (FR-04)
            case "NEAREST" -> "distance_km ASC, p.code";
            default -> "p.verified_at DESC NULLS LAST, p.code";
        };

        String from = PlaceReader.CARD_FROM + " WHERE " + where;
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();

        String distance = hasOrigin ? PlaceReader.DISTANCE_KM : "CAST(NULL AS double precision)";
        List<PlaceCard> items = jdbc.sql("SELECT " + PlaceReader.CARD_COLUMNS + ", " + distance + " AS distance_km"
                        + from + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", s)
                .param("offset", (long) p * s)
                .query((rs, i) -> reader.mapCardWithDistance(rs))
                .list();

        reader.attachTags(items);

        var meta = new PageMeta(p, s, total, (int) ((total + s - 1) / s));
        return ResponseEntity.ok(new PlaceSearchPage(UUID.randomUUID(), items, meta));
    }

    /** Chỉ địa điểm đang công khai; ẩn/ngừng/nháp đều 404 (PROP OQ-11). */
    @Override
    public ResponseEntity<PlaceDetail> getPlace(UUID placeId) {
        List<PlaceDetail> found = reader.details(List.of(placeId), null, null, true);
        if (found.isEmpty()) {
            throw Problems.notFound();
        }
        return ResponseEntity.ok(found.getFirst());
    }

    /** Đúng 2–3 địa điểm khác nhau, đều đang công khai (RULE-06); giữ thứ tự theo {@code ids}. */
    @Override
    public ResponseEntity<List<PlaceDetail>> comparePlaces(Set<UUID> ids, Double lat, Double lng) {
        // Set đã gộp id trùng: "a,a" thành 1 phần tử và bị chặn ở đây.
        if (ids == null || ids.size() < 2 || ids.size() > 3) {
            throw Problems.badRequest("Cần chọn 2 đến 3 địa điểm khác nhau",
                    List.of(Problems.fieldError("ids", "Số địa điểm khác nhau phải từ 2 đến 3")));
        }
        if ((lat == null) != (lng == null)) {
            throw Problems.badRequest("lat và lng phải đi cùng nhau",
                    List.of(Problems.fieldError(lat == null ? "lat" : "lng", "Thiếu tọa độ còn lại")));
        }
        List<PlaceDetail> found = reader.details(new ArrayList<>(ids), lat, lng, true);
        if (found.size() != ids.size()) {
            throw Problems.notFound();
        }
        return ResponseEntity.ok(found);
    }

    /**
     * Điểm gốc do người dùng cung cấp. shortcut: OQ-05 — NEAREST và bán kính bắt buộc có điểm gốc,
     * không tự đoán vị trí; khu vực và bán kính kết hợp AND.
     */
    private static boolean validateOrigin(Double lat, Double lng, Double radiusKm, String sort) {
        if ((lat == null) != (lng == null)) {
            throw Problems.badRequest("lat và lng phải đi cùng nhau",
                    List.of(Problems.fieldError(lat == null ? "lat" : "lng", "Thiếu tọa độ còn lại")));
        }
        boolean hasOrigin = lat != null;
        if (radiusKm != null && !hasOrigin) {
            throw Problems.badRequest("Lọc bán kính cần điểm gốc",
                    List.of(Problems.fieldError("radiusKm", "Cần lat và lng")));
        }
        if ("NEAREST".equals(sort) && !hasOrigin) {
            throw Problems.badRequest("Sắp xếp gần nhất cần điểm gốc",
                    List.of(Problems.fieldError("sort", "Cần lat và lng")));
        }
        return hasOrigin;
    }

    /**
     * RULE-02: các nhóm kết hợp AND; ACTIVITY/STYLE/SCENERY/OCCASION khớp một trong các nhãn chọn;
     * VIBE/FEATURE/AMENITY phải có đủ mọi nhãn chọn; CATEGORY so với loại hình chính của địa điểm.
     */
    private void appendTagFilter(List<UUID> tagIds, StringBuilder where, Map<String, Object> params) {
        Map<TaxonomyGroup, List<UUID>> byGroup = new LinkedHashMap<>();
        jdbc.sql("SELECT id, group_code FROM taxonomy WHERE id IN (:ids) ORDER BY group_code")
                .param("ids", Set.copyOf(tagIds))
                .query(rs -> {
                    byGroup.computeIfAbsent(TaxonomyGroup.fromValue(rs.getString("group_code")), g -> new ArrayList<>())
                            .add(rs.getObject("id", UUID.class));
                });
        long found = byGroup.values().stream().mapToLong(List::size).sum();
        if (found != Set.copyOf(tagIds).size()) {
            throw Problems.badRequest("Có nhãn không tồn tại",
                    List.of(Problems.fieldError("tagIds", "Có mã nhãn không tồn tại")));
        }
        byGroup.forEach((group, ids) -> {
            String key = "tags_" + group.getValue();
            params.put(key, ids);
            if (group == TaxonomyGroup.CATEGORY) {
                where.append(" AND p.category_id IN (:").append(key).append(")");
            } else if (ANY_OF_GROUPS.contains(group)) {
                where.append(" AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.taxonomy_id IN (:")
                        .append(key).append("))");
            } else {
                where.append(" AND (SELECT count(DISTINCT pt.taxonomy_id) FROM place_tag pt")
                        .append(" WHERE pt.place_id = p.id AND pt.taxonomy_id IN (:").append(key).append(")) = ")
                        .append(ids.size());
            }
        });
    }

    /**
     * RULE-05: chỉ nơi đã xác minh giờ và đang mở tại {@code at} (giờ Việt Nam). Giờ chưa rõ không đạt (RULE-04).
     * Ngày có ngoại lệ thì dùng ngoại lệ thay lịch tuần (PROP). Khoảng mở: opens ≤ t < closes.
     * shortcut: OQ-04 — khoảng có closes ≤ opens là qua đêm: đạt khi t ≥ opens trong ngày đó, hoặc t < closes
     * của khoảng qua đêm ngày hôm trước (ngày hôm trước cũng ưu tiên ngoại lệ). Chưa lọc theo thời lượng.
     */
    private static void appendOpenAtFilter(OffsetDateTime at, StringBuilder where, Map<String, Object> params) {
        ZonedDateTime local = at.atZoneSameInstant(PlaceReader.VN);
        LocalDate date = local.toLocalDate();
        LocalDate prev = date.minusDays(1);
        params.put("atDate", date);
        params.put("atDow", date.getDayOfWeek().getValue());
        params.put("atPrevDate", prev);
        params.put("atPrevDow", prev.getDayOfWeek().getValue());
        params.put("atTime", LocalTime.of(local.getHour(), local.getMinute(), local.getSecond()));
        where.append("""
                 AND p.hours_status = 'KNOWN' AND (
                   (CASE WHEN EXISTS (SELECT 1 FROM date_exception e WHERE e.place_id = p.id AND e.on_date = :atDate)
                     THEN EXISTS (SELECT 1 FROM date_exception e WHERE e.place_id = p.id AND e.on_date = :atDate
                                  AND NOT e.closed AND (
                                    (e.opens_at < e.closes_at AND :atTime >= e.opens_at AND :atTime < e.closes_at)
                                    OR (e.closes_at <= e.opens_at AND :atTime >= e.opens_at)))
                     ELSE EXISTS (SELECT 1 FROM opening_hours h WHERE h.place_id = p.id AND h.day_of_week = :atDow
                                  AND (
                                    (h.opens_at < h.closes_at AND :atTime >= h.opens_at AND :atTime < h.closes_at)
                                    OR (h.closes_at <= h.opens_at AND :atTime >= h.opens_at)))
                   END)
                   OR
                   (CASE WHEN EXISTS (SELECT 1 FROM date_exception e WHERE e.place_id = p.id AND e.on_date = :atPrevDate)
                     THEN EXISTS (SELECT 1 FROM date_exception e WHERE e.place_id = p.id AND e.on_date = :atPrevDate
                                  AND NOT e.closed AND e.closes_at <= e.opens_at AND :atTime < e.closes_at)
                     ELSE EXISTS (SELECT 1 FROM opening_hours h WHERE h.place_id = p.id AND h.day_of_week = :atPrevDow
                                  AND h.closes_at <= h.opens_at AND :atTime < h.closes_at)
                   END)
                 )
                """);
    }

    /** Bỏ dấu tiếng Việt + lowercase; cùng quy tắc phải dùng khi ghi place.search_text. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String noMarks = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
