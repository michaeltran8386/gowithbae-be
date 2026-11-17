package vn.gowithbae.shared;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.place.PlaceLookupService;

/**
 * Lớp mỏng cho các module của Người C (cùng chọn, kế hoạch) đọc địa điểm và giờ mở cửa.
 * Dữ liệu địa điểm lấy qua {@link PlaceLookupService} của phần B — không truy vấn thẳng bảng place/opening_hours/
 * date_exception — nên quy tắc giờ mở cửa luôn trùng với bộ lọc tìm kiếm (RULE-05, PROP OQ-04).
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

    public record ExceptionRow(LocalDate onDate, boolean closed, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {}

    public record TimeWindow(LocalTime opensAt, LocalTime closesAt) {}

    private final JdbcClient jdbc;
    private final PlaceLookupService places;

    public PlaceCatalogService(JdbcClient jdbc, PlaceLookupService places) {
        this.jdbc = jdbc;
        this.places = places;
    }

    public Optional<PlaceSummary> findPlace(UUID placeId) {
        return Optional.ofNullable(findPlaces(List.of(placeId)).get(placeId));
    }

    public Map<UUID, PlaceSummary> findPlaces(Collection<UUID> placeIds) {
        Map<UUID, PlaceSummary> byId = new LinkedHashMap<>();
        places.find(placeIds).forEach((id, s) -> byId.put(id,
                new PlaceSummary(s.id(), s.name(), s.address(), s.status(), s.hoursStatus(), s.card())));
        return byId;
    }

    public static PlaceCard unavailableCard(UUID placeId, String name) {
        return PlaceLookupService.unavailableCard(placeId, name);
    }

    /** Bảng saved_list thuộc phần A; chỉ đọc để kiểm tra ứng viên đều nằm trong danh sách đã lưu của người tạo. */
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
        if (!"KNOWN".equals(hoursStatus)) {
            return OpeningStatus.UNKNOWN;
        }
        return OpeningStatus.valueOf(places.openingAt(placeId, scheduledAt).name());
    }

    /** Giữ cho test/caller cũ; quy tắc thật nằm ở {@link PlaceLookupService#evaluate}. */
    public static OpeningStatus evaluateOpeningRules(
            String hoursStatus,
            ZonedDateTime vnTime,
            List<ExceptionRow> exceptions,
            List<TimeWindow> prevWeekly,
            List<TimeWindow> todayWeekly) {
        return OpeningStatus.valueOf(PlaceLookupService.evaluate(
                hoursStatus,
                vnTime,
                exceptions.stream().map(e -> new PlaceLookupService.ExceptionRow(e.onDate(), e.closed(), e.opensAt(), e.closesAt())).toList(),
                prevWeekly.stream().map(w -> new PlaceLookupService.TimeWindow(w.opensAt(), w.closesAt())).toList(),
                todayWeekly.stream().map(w -> new PlaceLookupService.TimeWindow(w.opensAt(), w.closesAt())).toList())
                .name());
    }
}
