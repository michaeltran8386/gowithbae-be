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

import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.common.TimeHelper;

/**
 * Truy vấn đọc địa điểm, danh sách đã lưu và giờ mở cửa phục vụ các module của Người C
 * (giữ tách biệt khỏi bean vn.gowithbae.platform.PlaceQueryService mà Người B sẽ cung cấp).
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
            String address,
            String status,
            String hoursStatus,
            PlaceCard card) {

        public boolean isPublished() {
            return "PUBLISHED".equals(status);
        }
    }

    private final JdbcClient jdbc;

    public PlaceCatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlaceSummary> findPlace(UUID placeId) {
        return Optional.ofNullable(findPlaces(List.of(placeId)).get(placeId));
    }

    public Map<UUID, PlaceSummary> findPlaces(Collection<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<UUID> distinctIds = Set.copyOf(placeIds);
        List<PlaceSummary> rows = jdbc.sql("""
                SELECT p.id, p.name, p.address, p.status, p.hours_status, p.lat, p.lng,
                       a.name AS area_name,
                       COALESCE(pp.status, 'UNKNOWN') AS price_status,
                       pp.standard_for_two, pp.min_price, pp.max_price, pp.includes, pp.verified_at AS price_verified_at
                FROM place p
                JOIN area a ON a.id = p.area_id
                LEFT JOIN place_price pp ON pp.place_id = p.id
                WHERE p.id IN (:ids)
                """)
                .param("ids", distinctIds)
                .query((rs, rowNum) -> {
                    Price price = new Price(KnownStatus.fromValue(rs.getString("price_status")))
                            .standardForTwo(rs.getObject("standard_for_two", Long.class))
                            .min(rs.getObject("min_price", Long.class))
                            .max(rs.getObject("max_price", Long.class))
                            .includes(rs.getString("includes"))
                            .verifiedAt(rs.getObject("price_verified_at", LocalDate.class));
                    UUID id = rs.getObject("id", UUID.class);
                    PlaceCard card = new PlaceCard(id, rs.getString("name"), price, new ArrayList<>())
                            .areaName(rs.getString("area_name"))
                            .coverUrl(null)
                            .lat(rs.getObject("lat", Double.class))
                            .lng(rs.getObject("lng", Double.class));
                    return new PlaceSummary(
                            id,
                            rs.getString("name"),
                            rs.getString("address"),
                            rs.getString("status"),
                            rs.getString("hours_status"),
                            card);
                })
                .list();

        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<UUID, PlaceSummary> byId = new LinkedHashMap<>();
        for (PlaceSummary summary : rows) {
            byId.put(summary.id(), summary);
        }

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
                    PlaceSummary target = byId.get(rs.getObject("place_id", UUID.class));
                    if (target != null) {
                        target.card().addTagsItem(new Taxonomy(
                                rs.getObject("id", UUID.class),
                                TaxonomyGroup.fromValue(rs.getString("group_code")),
                                rs.getString("code"),
                                rs.getString("name"),
                                ActiveStatus.ACTIVE));
                    }
                });

        return byId;
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
        if (closesAt.isAfter(opensAt)) {
            return !time.isBefore(opensAt) && !time.isAfter(closesAt);
        }
        return !time.isBefore(opensAt);
    }

    private static boolean matchesOvernightTail(LocalTime time, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {
        if (opensAt == null || closesAt == null) {
            return false;
        }
        return !closesAt.isAfter(opensAt) && !time.isAfter(closesAt);
    }

    public record ExceptionRow(LocalDate onDate, boolean closed, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {}

    public record TimeWindow(LocalTime opensAt, LocalTime closesAt) {}
}
