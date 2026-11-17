package vn.gowithbae.place;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Price;

/**
 * Cửa duy nhất để module khác (cùng chọn, kế hoạch, báo sai, số lần đã đi) đọc dữ liệu địa điểm của phần B.
 * Module khác không truy vấn thẳng bảng place / opening_hours / date_exception; B đổi schema chỉ cần sửa ở đây.
 */
@Service
public class PlaceLookupService {

    /** Ảnh chụp địa điểm cho module khác. Nơi không còn PUBLISHED chỉ giữ id + tên (PROP OQ-11). */
    public record PlaceSnapshot(UUID id, String name, @Nullable String address, String status, String hoursStatus,
            PlaceCard card) {

        public boolean isPublished() {
            return "PUBLISHED".equals(status);
        }
    }

    public enum OpeningStatus {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    public record ExceptionRow(LocalDate onDate, boolean closed, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {
    }

    public record TimeWindow(LocalTime opensAt, LocalTime closesAt) {
    }

    private final JdbcClient jdbc;
    private final PlaceReader reader;

    public PlaceLookupService(JdbcClient jdbc, PlaceReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    // ───────────── Hồ sơ rút gọn ─────────────

    public Map<UUID, PlaceSnapshot> find(Collection<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = Set.copyOf(placeIds);
        Map<UUID, PlaceSnapshot> byId = new LinkedHashMap<>();
        List<UUID> publishedIds = new ArrayList<>();
        jdbc.sql("SELECT id, name, status FROM place WHERE id IN (:ids)")
                .param("ids", ids)
                .query(rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String status = rs.getString("status");
                    if ("PUBLISHED".equals(status)) {
                        publishedIds.add(id);
                    } else {
                        // Không lộ giá/ảnh/nhãn/địa chỉ/giờ của nơi đã ẩn hoặc ngừng (PROP OQ-11).
                        byId.put(id, new PlaceSnapshot(id, rs.getString("name"), null, status, "UNKNOWN",
                                unavailableCard(id, rs.getString("name"))));
                    }
                });
        if (!publishedIds.isEmpty()) {
            List<PlaceCard> cards = new ArrayList<>();
            jdbc.sql("SELECT p.address, p.status, p.hours_status, " + PlaceReader.CARD_COLUMNS + PlaceReader.CARD_FROM
                            + " WHERE p.id IN (:ids)")
                    .param("ids", publishedIds)
                    .query(rs -> {
                        PlaceCard card = reader.mapCard(rs);
                        cards.add(card);
                        byId.put(card.getId(), new PlaceSnapshot(card.getId(), card.getName(), rs.getString("address"),
                                rs.getString("status"), rs.getString("hours_status"), card));
                    });
            reader.attachTags(cards);
        }
        return byId;
    }

    /** Tên địa điểm (mọi trạng thái) — dùng cho màn quản trị cần hiện tên nơi bị báo sai. */
    public Map<UUID, String> names(Collection<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, name FROM place WHERE id IN (:ids)")
                .param("ids", Set.copyOf(placeIds))
                .query(rs -> {
                    names.put(rs.getObject("id", UUID.class), rs.getString("name"));
                });
        return names;
    }

    /** Địa điểm từng được công khai (đang công khai, đã ẩn hoặc ngừng) — chưa từng duyệt thì coi như không tồn tại. */
    public boolean everPublished(UUID placeId) {
        return placeId != null && jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM place WHERE id = :id AND status IN ('PUBLISHED', 'HIDDEN', 'INACTIVE'))
                """).param("id", placeId).query(Boolean.class).single();
    }

    /** Id địa điểm theo khu vực và/hoặc loại hình (null = không lọc theo tiêu chí đó) — dùng cho báo cáo. */
    public Set<UUID> idsIn(@Nullable UUID areaId, @Nullable UUID categoryId) {
        return Set.copyOf(jdbc.sql("""
                SELECT id FROM place
                WHERE (CAST(:area AS uuid) IS NULL OR area_id = CAST(:area AS uuid))
                  AND (CAST(:category AS uuid) IS NULL OR category_id = CAST(:category AS uuid))
                """).param("area", areaId).param("category", categoryId).query(UUID.class).list());
    }

    public static PlaceCard unavailableCard(UUID placeId, String name) {
        return new PlaceCard(placeId, name, new Price(KnownStatus.UNKNOWN), new ArrayList<>());
    }

    // ───────────── Giờ mở cửa ─────────────

    /** Trạng thái mở cửa tại thời điểm {@code at} (giờ Việt Nam), cùng quy tắc với bộ lọc {@code at} của tìm kiếm. */
    public OpeningStatus openingAt(UUID placeId, OffsetDateTime at) {
        String hoursStatus = jdbc.sql("SELECT hours_status FROM place WHERE id = :id")
                .param("id", placeId).query(String.class).optional().orElse("UNKNOWN");
        ZonedDateTime local = at.atZoneSameInstant(PlaceReader.VN);
        LocalDate date = local.toLocalDate();
        LocalDate prev = date.minusDays(1);
        List<ExceptionRow> exceptions = jdbc.sql("""
                SELECT on_date, closed, opens_at, closes_at FROM date_exception
                WHERE place_id = :id AND on_date IN (:prev, :today)
                """)
                .param("id", placeId).param("prev", prev).param("today", date)
                .query((rs, i) -> new ExceptionRow(rs.getObject("on_date", LocalDate.class), rs.getBoolean("closed"),
                        rs.getObject("opens_at", LocalTime.class), rs.getObject("closes_at", LocalTime.class)))
                .list();
        return evaluate(hoursStatus, local, exceptions, weekly(placeId, prev), weekly(placeId, date));
    }

    /**
     * Quy tắc dùng chung (RULE-05, PROP OQ-04), phải khớp SQL lọc {@code at} trong {@link PlacesDelegate}:
     * <ul>
     * <li>Giờ chưa rõ → UNKNOWN (không đạt lọc "đang mở", chỉ cảnh báo khi lên kế hoạch).</li>
     * <li>Hôm nay: có ngoại lệ thì dùng ngoại lệ thay lịch tuần. Khoảng [opens, closes); closes ≤ opens là qua đêm.</li>
     * <li>Phần qua đêm của hôm trước (cũng ưu tiên ngoại lệ hôm trước) vẫn tính, kể cả khi hôm nay có ngoại lệ đóng cửa.</li>
     * </ul>
     */
    public static OpeningStatus evaluate(String hoursStatus, ZonedDateTime local, List<ExceptionRow> exceptions,
            List<TimeWindow> prevWeekly, List<TimeWindow> todayWeekly) {
        if (!"KNOWN".equals(hoursStatus)) {
            return OpeningStatus.UNKNOWN;
        }
        LocalDate date = local.toLocalDate();
        LocalDate prev = date.minusDays(1);
        LocalTime t = local.toLocalTime();

        List<ExceptionRow> today = exceptions.stream().filter(e -> date.equals(e.onDate())).toList();
        boolean openToday = today.isEmpty()
                ? todayWeekly.stream().anyMatch(w -> startsCovering(t, w.opensAt(), w.closesAt()))
                : today.stream().anyMatch(e -> !e.closed() && startsCovering(t, e.opensAt(), e.closesAt()));

        List<ExceptionRow> yesterday = exceptions.stream().filter(e -> prev.equals(e.onDate())).toList();
        boolean overnightTail = yesterday.isEmpty()
                ? prevWeekly.stream().anyMatch(w -> tailCovering(t, w.opensAt(), w.closesAt()))
                : yesterday.stream().anyMatch(e -> !e.closed() && tailCovering(t, e.opensAt(), e.closesAt()));

        return openToday || overnightTail ? OpeningStatus.OPEN : OpeningStatus.CLOSED;
    }

    /** Khoảng bắt đầu trong ngày: [opens, closes) thường, hoặc t ≥ opens nếu qua đêm. */
    private static boolean startsCovering(LocalTime t, @Nullable LocalTime opens, @Nullable LocalTime closes) {
        if (opens == null || closes == null) {
            return false;
        }
        return opens.isBefore(closes) ? !t.isBefore(opens) && t.isBefore(closes) : !t.isBefore(opens);
    }

    /** Phần sáng hôm sau của khoảng qua đêm: t < closes. */
    private static boolean tailCovering(LocalTime t, @Nullable LocalTime opens, @Nullable LocalTime closes) {
        return opens != null && closes != null && !closes.isAfter(opens) && t.isBefore(closes);
    }

    private List<TimeWindow> weekly(UUID placeId, LocalDate date) {
        return jdbc.sql("SELECT opens_at, closes_at FROM opening_hours WHERE place_id = :id AND day_of_week = :dow")
                .param("id", placeId)
                .param("dow", date.getDayOfWeek().getValue())
                .query((rs, i) -> new TimeWindow(rs.getObject("opens_at", LocalTime.class), rs.getObject("closes_at", LocalTime.class)))
                .list();
    }
}
