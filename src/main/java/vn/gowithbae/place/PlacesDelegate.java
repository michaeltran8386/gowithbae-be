package vn.gowithbae.place;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.PlacesApiDelegate;
import vn.gowithbae.api.model.BudgetBand;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.PlaceSearchPage;

/**
 * GET /places — endpoint mẫu chạy thật (FR-02, FR-03 phần khu vực/ngân sách, FR-04 phần sắp xếp).
 * Các operation khác của tag Places vẫn trả 501 từ default method của PlacesApiDelegate.
 */
@Service
public class PlacesDelegate implements PlacesApiDelegate {

    private final JdbcClient jdbc;
    private final PlaceReader reader;

    public PlacesDelegate(JdbcClient jdbc, PlaceReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    @Override
    public ResponseEntity<PlaceSearchPage> searchPlaces(String q, UUID areaId, Double lat, Double lng, Double radiusKm,
            BudgetBand budget, OffsetDateTime at, List<UUID> tagIds, String sort, Integer page, Integer size) {
        // shortcut: lọc nhãn (RULE-02), giờ mở cửa (RULE-05), vị trí/NEAREST (OQ-05) chưa làm; trả 501 thay vì bỏ qua âm thầm.
        if (lat != null || lng != null || radiusKm != null || at != null
                || (tagIds != null && !tagIds.isEmpty()) || "NEAREST".equals(sort)) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Bộ lọc vị trí/giờ/nhãn chưa được triển khai");
        }
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;

        var where = new StringBuilder("p.status = 'PUBLISHED'");
        var params = new java.util.HashMap<String, Object>();
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
        String orderBy = "PRICE_ASC".equals(sort)
                ? "pp.standard_for_two ASC NULLS LAST, p.code" // giá chưa rõ xếp cuối (FR-04)
                : "p.verified_at DESC NULLS LAST, p.code";

        String from = PlaceReader.CARD_FROM + " WHERE " + where;

        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();

        List<PlaceCard> items = jdbc.sql("SELECT " + PlaceReader.CARD_COLUMNS + from
                        + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", s)
                .param("offset", (long) p * s)
                .query((rs, i) -> reader.mapCard(rs))
                .list();

        reader.attachTags(items);

        var meta = new PageMeta(p, s, total, (int) ((total + s - 1) / s));
        return ResponseEntity.ok(new PlaceSearchPage(UUID.randomUUID(), items, meta));
    }

    /** Bỏ dấu tiếng Việt + lowercase; cùng quy tắc phải dùng khi ghi place.search_text. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String noMarks = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(java.util.Locale.ROOT);
    }
}
