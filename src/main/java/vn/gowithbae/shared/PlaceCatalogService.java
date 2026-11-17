package vn.gowithbae.shared;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.place.PlaceReader;

/**
 * Truy vấn đọc địa điểm, danh sách đã lưu và giờ mở cửa phục vụ các module của Người C,
 * dùng lại {@link PlaceReader} (phần B) và tuân thủ quy tắc ẩn dữ liệu khi địa điểm không còn {@code PUBLISHED} (PROP OQ-11).
 */
@Service
public class PlaceCatalogService {

    public enum OpeningStatus {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    public record PlaceSummary(
            UUID id,
            String name,
            @Nullable String address,
            String status,
            String hoursStatus,
            PlaceCard card) {

        public boolean isPublished() {
            return "PUBLISHED".equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final PlaceReader placeReader;

    public PlaceCatalogService(JdbcClient jdbc, PlaceReader placeReader) {
        this.jdbc = jdbc;
        this.placeReader = placeReader;
    }

    public Optional<PlaceSummary> findPlace(UUID placeId) {
        return Optional.ofNullable(findPlaces(List.of(placeId)).get(placeId));
    }

    public Map<UUID, PlaceSummary> findPlaces(Collection<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<UUID> distinctIds = Set.copyOf(placeIds);
        record MetaRow(UUID id, String name, String status, String hoursStatus) {}
        List<MetaRow> metaRows = jdbc.sql("""
                SELECT id, name, status, hours_status
                FROM place
                WHERE id IN (:ids)
                """)
                .param("ids", distinctIds)
                .query((rs, rowNum) -> new MetaRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getString("hours_status")))
                .list();

        if (metaRows.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UUID> publishedIds = new ArrayList<>();
        Map<UUID, PlaceSummary> byId = new LinkedHashMap<>();
        for (MetaRow row : metaRows) {
            if ("PUBLISHED".equals(row.status())) {
                publishedIds.add(row.id());
            } else {
                // Khớp PROP OQ-11 của PlaceQueryServiceImpl: chỉ giữ id và tên, không lộ giá/ảnh/nhãn/địa chỉ.
                byId.put(row.id(), new PlaceSummary(
                        row.id(),
                        row.name(),
                        null,
                        row.status(),
                        "UNKNOWN",
                        unavailableCard(row.id(), row.name())));
            }
        }

        if (!publishedIds.isEmpty()) {
            List<PlaceSummary> publishedSummaries = jdbc.sql("SELECT p.address, p.status, p.hours_status, "
                            + PlaceReader.CARD_COLUMNS + PlaceReader.CARD_FROM + " WHERE p.id IN (:ids)")
                    .param("ids", publishedIds)
                    .query((rs, rowNum) -> {
                        PlaceCard card = placeReader.mapCard(rs);
                        return new PlaceSummary(
                                card.getId(),
                                card.getName(),
                                rs.getString("address"),
                                rs.getString("status"),
                                rs.getString("hours_status"),
                                card);
                    })
                    .list();
            List<PlaceCard> cards = new ArrayList<>(publishedSummaries.size());
            for (PlaceSummary summary : publishedSummaries) {
                cards.add(summary.card());
                byId.put(summary.id(), summary);
            }
            placeReader.attachTags(cards);
        }

        return byId;
    }

    public static PlaceCard unavailableCard(UUID placeId, String name) {
        return new PlaceCard(placeId, name, new Price(KnownStatus.UNKNOWN), new ArrayList<>());
    }

    public boolean areAllPlacesSavedByUser(UUID userId, Set<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return false;
        }
        Long count = jdbc.sql("""
                SELECT COUNT(DISTINCT sli.place_id)
                FROM saved_list_item sli
                JOIN saved_list sl ON sl.id = sli.list_id
                WHERE sl.owner_id = :userId AND sli.place_id IN (:placeIds)
                """)
                .param("userId", userId)
                .param("placeIds", placeIds)
                .query(Long.class)
                .single();
        return count != null && count == placeIds.size();
    }

    public OpeningStatus evaluateOpeningAt(UUID placeId, String hoursStatus, OffsetDateTime scheduledAt) {
        ZonedDateTime vnTime = TimeHelper.inVietnam(scheduledAt);
        LocalDate date = vnTime.toLocalDate();
        LocalDate prevDate = date.minusDays(1);

        List<ExceptionRow> exceptions = jdbc.sql("""
                SELECT on_date, closed, opens_at, closes_at
                FROM date_exception
                WHERE place_id = :placeId AND on_date IN (:prevDate, :currDate)
                """)
                .param("placeId", placeId)
                .param("prevDate", prevDate)
                .param("currDate", date)
                .query((rs, rowNum) -> new ExceptionRow(
                        rs.getObject("on_date", LocalDate.class),
                        rs.getBoolean("closed"),
                        rs.getObject("opens_at", LocalTime.class),
                        rs.getObject("closes_at", LocalTime.class)))
                .list();

        List<TimeWindow> prevWeekly = loadWeeklyHours(placeId, prevDate.getDayOfWeek().getValue());
        List<TimeWindow> todayWeekly = "KNOWN".equals(hoursStatus)
                ? loadWeeklyHours(placeId, date.getDayOfWeek().getValue())
                : Collections.emptyList();

        return evaluateOpeningRules(hoursStatus, vnTime, exceptions, prevWeekly, todayWeekly);
    }

    public static OpeningStatus evaluateOpeningRules(
            String hoursStatus,
            ZonedDateTime vnTime,
            List<ExceptionRow> exceptions,
            List<TimeWindow> prevWeekly,
            List<TimeWindow> todayWeekly) {
        LocalDate date = vnTime.toLocalDate();
        LocalTime time = vnTime.toLocalTime();
        LocalDate prevDate = date.minusDays(1);

        List<ExceptionRow> todayExceptions = exceptions.stream().filter(e -> date.equals(e.onDate())).toList();
        if (!todayExceptions.isEmpty()) {
            for (ExceptionRow ex : todayExceptions) {
                if (!ex.closed() && matchesSameDayOrOvernightStart(time, ex.opensAt(), ex.closesAt())) {
                    return OpeningStatus.OPEN;
                }
            }
            return OpeningStatus.CLOSED;
        }

        List<ExceptionRow> yesterdayExceptions = exceptions.stream().filter(e -> prevDate.equals(e.onDate())).toList();
        if (!yesterdayExceptions.isEmpty()) {
            for (ExceptionRow ex : yesterdayExceptions) {
                if (!ex.closed() && matchesOvernightTail(time, ex.opensAt(), ex.closesAt())) {
                    return OpeningStatus.OPEN;
                }
            }
        } else {
            for (TimeWindow w : prevWeekly) {
                if (matchesOvernightTail(time, w.opensAt(), w.closesAt())) {
                    return OpeningStatus.OPEN;
                }
            }
        }

        if (!"KNOWN".equals(hoursStatus)) {
            return OpeningStatus.UNKNOWN;
        }

        for (TimeWindow w : todayWeekly) {
            if (matchesSameDayOrOvernightStart(time, w.opensAt(), w.closesAt())) {
                return OpeningStatus.OPEN;
            }
        }
        return OpeningStatus.CLOSED;
    }

    private List<TimeWindow> loadWeeklyHours(UUID placeId, int dayOfWeek) {
        return jdbc.sql("""
                SELECT opens_at, closes_at
                FROM opening_hours
                WHERE place_id = :placeId AND day_of_week = :dow
                """)
                .param("placeId", placeId)
                .param("dow", dayOfWeek)
                .query((rs, rowNum) -> new TimeWindow(
                        rs.getObject("opens_at", LocalTime.class),
                        rs.getObject("closes_at", LocalTime.class)))
                .list();
    }

    private static boolean matchesSameDayOrOvernightStart(LocalTime time, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {
        if (opensAt == null || closesAt == null) {
            return false;
        }
        // Khớp quy tắc [opens_at, closes_at) của PlacesDelegate (phần B, OQ-04)
        if (closesAt.isAfter(opensAt)) {
            return !time.isBefore(opensAt) && time.isBefore(closesAt);
        }
        return !time.isBefore(opensAt);
    }

    private static boolean matchesOvernightTail(LocalTime time, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {
        if (opensAt == null || closesAt == null) {
            return false;
        }
        return !closesAt.isAfter(opensAt) && time.isBefore(closesAt);
    }

    public record ExceptionRow(LocalDate onDate, boolean closed, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {}

    public record TimeWindow(LocalTime opensAt, LocalTime closesAt) {}
}
