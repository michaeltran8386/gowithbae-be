package vn.gowithbae.shared;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.gowithbae.api.model.PlaceCard;

/** Hợp đồng đọc thông tin địa điểm, kiểm tra danh sách đã lưu và giờ mở cửa theo TEAM_SPLIT (cung cấp bởi Người B). */
public interface PlaceQueryService {

    enum OpeningStatus {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    record PlaceSummary(
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

    Optional<PlaceSummary> findPlace(UUID placeId);

    Map<UUID, PlaceSummary> findPlaces(Collection<UUID> placeIds);

    boolean areAllPlacesSavedByUser(UUID userId, Set<UUID> placeIds);

    OpeningStatus evaluateOpeningAt(UUID placeId, String hoursStatus, OffsetDateTime scheduledAt);
}
