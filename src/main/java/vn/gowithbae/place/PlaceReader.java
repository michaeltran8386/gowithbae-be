package vn.gowithbae.place;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.Area;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.OpeningHours;
import vn.gowithbae.api.model.OpeningHoursExceptionsInner;
import vn.gowithbae.api.model.OpeningHoursWeeklyInner;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.PlaceDetail;
import vn.gowithbae.api.model.PlaceImage;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.api.model.TimeRange;
import vn.gowithbae.common.files.FileUrls;

/**
 * SQL dùng chung để dựng {@link PlaceCard} và {@link PlaceDetail}: tìm kiếm, bộ sưu tập, so sánh, quản trị.
 * Thẻ: {@code SELECT } + {@link #CARD_COLUMNS} + {@link #CARD_FROM} + điều kiện riêng.
 */
@Component
public class PlaceReader {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public static final String CARD_COLUMNS = """
            p.id, p.name, p.lat, p.lng, a.name AS area_name,
            coalesce(pp.status, 'UNKNOWN') AS price_status, pp.standard_for_two, pp.min_price, pp.max_price,
            pp.includes, pp.verified_at AS price_verified_at, cover_file.storage_key AS cover_key
            """;

    /** Alias: p = place, pp = place_price, a = area, cover = ảnh bìa, cover_file = tệp ảnh bìa. */
    public static final String CARD_FROM = """
             FROM place p
             LEFT JOIN place_price pp ON pp.place_id = p.id
             LEFT JOIN area a ON a.id = p.area_id
             LEFT JOIN place_image cover ON cover.place_id = p.id AND cover.is_cover
             LEFT JOIN stored_file cover_file ON cover_file.id = cover.file_id
            """;

    /**
     * Khoảng cách đường thẳng (km, haversine) từ điểm gốc {@code :originLat, :originLng} tới {@code p}.
     * shortcut: OQ-05 chưa chốt khoảng cách thẳng hay đường đi; đây chỉ là ước tính.
     */
    public static final String DISTANCE_KM = """
            (6371 * 2 * asin(sqrt(
                power(sin(radians(p.lat - :originLat) / 2), 2)
                + cos(radians(:originLat)) * cos(radians(p.lat)) * power(sin(radians(p.lng - :originLng) / 2), 2))))
            """;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcClient jdbc;
    private final FileUrls fileUrls;

    public PlaceReader(JdbcClient jdbc, FileUrls fileUrls) {
        this.jdbc = jdbc;
        this.fileUrls = fileUrls;
    }

    /** Map một dòng có đủ {@link #CARD_COLUMNS}; nhãn gắn sau bằng {@link #attachTags}. */
    public PlaceCard mapCard(ResultSet rs) throws SQLException {
        return new PlaceCard(rs.getObject("id", UUID.class), rs.getString("name"), mapPrice(rs), new ArrayList<>())
                .areaName(rs.getString("area_name"))
                .coverUrl(fileUrls.publicUrl(rs.getString("cover_key")))
                .lat(rs.getDouble("lat"))
                .lng(rs.getDouble("lng"));
    }

    /** Như {@link #mapCard(ResultSet)}, thêm cột {@code distance_km} (null khi request không có điểm gốc). */
    public PlaceCard mapCardWithDistance(ResultSet rs) throws SQLException {
        return mapCard(rs).distanceKm(roundKm(rs.getObject("distance_km", Double.class)));
    }

    /** Giá chưa biết trả status UNKNOWN, các số để null (RULE-04). */
    public static Price mapPrice(ResultSet rs) throws SQLException {
        return new Price(KnownStatus.fromValue(rs.getString("price_status")))
                .standardForTwo(rs.getObject("standard_for_two", Long.class))
                .min(rs.getObject("min_price", Long.class))
                .max(rs.getObject("max_price", Long.class))
                .includes(rs.getString("includes"))
                .verifiedAt(rs.getObject("price_verified_at", LocalDate.class));
    }

    /** Tối đa 3 nhãn đang dùng trên thẻ, không gồm loại hình (FR-02). */
    public void attachTags(List<PlaceCard> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<UUID, PlaceCard> byId = items.stream()
                .collect(Collectors.toMap(PlaceCard::getId, Function.identity(), (x, y) -> x));
        jdbc.sql("""
                SELECT place_id, id, group_code, code, name FROM (
                  SELECT pt.place_id, t.id, t.group_code, t.code, t.name,
                         row_number() OVER (PARTITION BY pt.place_id ORDER BY t.group_code, t.code) AS rn
                  FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
                  WHERE pt.place_id IN (:ids) AND t.status = 'ACTIVE' AND t.group_code <> 'CATEGORY'
                ) x WHERE rn <= 3
                """)
                .param("ids", byId.keySet())
                .query(rs -> {
                    byId.get(rs.getObject("place_id", UUID.class)).addTagsItem(mapTaxonomy(rs));
                });
    }

    /**
     * Hồ sơ đầy đủ theo thứ tự {@code ids}; id không tìm thấy (hoặc không PUBLISHED khi {@code publishedOnly})
     * bị bỏ qua — bên gọi tự quyết định 404. Trường thiếu dữ liệu để null (RULE-04).
     */
    public List<PlaceDetail> details(Collection<UUID> ids, Double originLat, Double originLng, boolean publishedOnly) {
        if (ids.isEmpty()) {
            return List.of();
        }
        boolean hasOrigin = originLat != null && originLng != null;
        var query = jdbc.sql("""
                SELECT p.id, p.code, p.name, p.description, p.address, p.lat, p.lng, p.phone, p.website, p.notes,
                       p.reservation_recommended, p.hours_status, p.verified_at,
                       coalesce(pp.status, 'UNKNOWN') AS price_status, pp.standard_for_two, pp.min_price, pp.max_price,
                       pp.includes, pp.verified_at AS price_verified_at,
                       c.id AS category_id, c.group_code AS category_group, c.code AS category_code,
                       c.name AS category_name, c.status AS category_status,
                       a.id AS area_id, a.code AS area_code, a.name AS area_name, a.status AS area_status,
                """ + (hasOrigin ? DISTANCE_KM : "CAST(NULL AS double precision)") + """
                        AS distance_km
                FROM place p
                LEFT JOIN place_price pp ON pp.place_id = p.id
                LEFT JOIN taxonomy c ON c.id = p.category_id
                LEFT JOIN area a ON a.id = p.area_id
                WHERE p.id IN (:ids)
                """ + (publishedOnly ? " AND p.status = 'PUBLISHED'" : ""))
                .param("ids", ids);
        if (hasOrigin) {
            query = query.param("originLat", originLat).param("originLng", originLng);
        }
        Map<UUID, PlaceDetail> byId = new LinkedHashMap<>();
        query.query(rs -> {
            UUID id = rs.getObject("id", UUID.class);
            var detail = new PlaceDetail(id, rs.getString("code"), rs.getString("name"), new ArrayList<>(),
                    mapPrice(rs), new ArrayList<>(),
                    new OpeningHours(KnownStatus.fromValue(rs.getString("hours_status"))))
                    .category(new Taxonomy(rs.getObject("category_id", UUID.class),
                            TaxonomyGroup.fromValue(rs.getString("category_group")), rs.getString("category_code"),
                            rs.getString("category_name"), ActiveStatus.fromValue(rs.getString("category_status"))))
                    .area(new Area(rs.getObject("area_id", UUID.class), rs.getString("area_code"),
                            rs.getString("area_name"), ActiveStatus.fromValue(rs.getString("area_status"))))
                    .description(rs.getString("description"))
                    .address(rs.getString("address"))
                    .lat(rs.getDouble("lat"))
                    .lng(rs.getDouble("lng"))
                    .phone(rs.getString("phone"))
                    .website(rs.getString("website"))
                    .notes(rs.getString("notes"))
                    .reservationRecommended(rs.getObject("reservation_recommended", Boolean.class))
                    .distanceKm(roundKm(rs.getObject("distance_km", Double.class)))
                    .verifiedAt(rs.getObject("verified_at", LocalDate.class));
            byId.put(id, detail);
        });
        if (byId.isEmpty()) {
            return List.of();
        }
        attachDetailTags(byId);
        attachImages(byId);
        attachHours(byId);
        return ids.stream().map(byId::get).filter(d -> d != null).toList();
    }

    /** Mọi nhãn đang dùng (không gồm loại hình). Nhãn ngừng dùng ẩn khỏi người dùng nhưng vẫn lưu (RULE-22). */
    private void attachDetailTags(Map<UUID, PlaceDetail> byId) {
        jdbc.sql("""
                SELECT pt.place_id, t.id, t.group_code, t.code, t.name
                FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
                WHERE pt.place_id IN (:ids) AND t.status = 'ACTIVE' AND t.group_code <> 'CATEGORY'
                ORDER BY t.group_code, t.code
                """)
                .param("ids", byId.keySet())
                .query(rs -> {
                    byId.get(rs.getObject("place_id", UUID.class)).addTagsItem(mapTaxonomy(rs));
                });
    }

    /** shortcut: OQ-21 chưa có nơi lưu tệp; khi chưa cấu hình URL gốc thì bỏ ảnh vì {@code url} bắt buộc trong contract. */
    private void attachImages(Map<UUID, PlaceDetail> byId) {
        jdbc.sql("""
                SELECT i.place_id, i.id, i.caption, i.sort_order, i.is_cover, f.storage_key
                FROM place_image i JOIN stored_file f ON f.id = i.file_id
                WHERE i.place_id IN (:ids)
                ORDER BY i.is_cover DESC, i.sort_order, i.id
                """)
                .param("ids", byId.keySet())
                .query(rs -> {
                    var url = fileUrls.publicUrl(rs.getString("storage_key"));
                    if (url == null) {
                        return;
                    }
                    byId.get(rs.getObject("place_id", UUID.class)).addImagesItem(
                            new PlaceImage(rs.getObject("id", UUID.class), url, rs.getInt("sort_order"),
                                    rs.getBoolean("is_cover"))
                                    .caption(rs.getString("caption")));
                });
    }

    /**
     * Lịch tuần + ngoại lệ từ hôm nay (giờ VN) trở đi. Giờ chưa rõ (UNKNOWN) trả danh sách rỗng, FE hiện "Chưa có thông tin".
     * Khoảng có closesAt ≤ opensAt là qua đêm (OQ-04).
     */
    private void attachHours(Map<UUID, PlaceDetail> byId) {
        Map<UUID, TreeMap<Integer, List<TimeRange>>> weekly = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT place_id, day_of_week, opens_at, closes_at FROM opening_hours
                WHERE place_id IN (:ids) ORDER BY day_of_week, opens_at
                """)
                .param("ids", byId.keySet())
                .query(rs -> {
                    weekly.computeIfAbsent(rs.getObject("place_id", UUID.class), k -> new TreeMap<>())
                            .computeIfAbsent(rs.getInt("day_of_week"), k -> new ArrayList<>())
                            .add(range(rs.getObject("opens_at", LocalTime.class), rs.getObject("closes_at", LocalTime.class)));
                });
        weekly.forEach((placeId, days) -> {
            OpeningHours hours = byId.get(placeId).getHours();
            if (hours.getStatus() == KnownStatus.KNOWN) {
                days.forEach((dow, ranges) -> hours.addWeeklyItem(new OpeningHoursWeeklyInner(dow, ranges)));
            }
        });

        jdbc.sql("""
                SELECT place_id, on_date, closed, opens_at, closes_at, note FROM date_exception
                WHERE place_id IN (:ids) AND on_date >= :today ORDER BY on_date
                """)
                .param("ids", byId.keySet())
                .param("today", LocalDate.now(VN))
                .query(rs -> {
                    OpeningHours hours = byId.get(rs.getObject("place_id", UUID.class)).getHours();
                    if (hours.getStatus() != KnownStatus.KNOWN) {
                        return;
                    }
                    var ex = new OpeningHoursExceptionsInner(rs.getObject("on_date", LocalDate.class), rs.getBoolean("closed"))
                            .note(rs.getString("note"));
                    if (!ex.getClosed()) {
                        ex.addRangesItem(range(rs.getObject("opens_at", LocalTime.class), rs.getObject("closes_at", LocalTime.class)));
                    }
                    hours.addExceptionsItem(ex);
                });
    }

    private static TimeRange range(LocalTime opens, LocalTime closes) {
        return new TimeRange(opens.format(HH_MM), closes.format(HH_MM));
    }

    private static Taxonomy mapTaxonomy(ResultSet rs) throws SQLException {
        return new Taxonomy(
                rs.getObject("id", UUID.class),
                TaxonomyGroup.fromValue(rs.getString("group_code")),
                rs.getString("code"),
                rs.getString("name"),
                ActiveStatus.ACTIVE);
    }

    /** Làm tròn 0,1 km: đây là ước tính, không nên hiển thị độ chính xác giả. */
    private static Double roundKm(Double km) {
        return km == null ? null : Math.round(km * 10) / 10.0;
    }
}
