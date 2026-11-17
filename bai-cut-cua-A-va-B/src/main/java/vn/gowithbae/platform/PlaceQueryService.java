package vn.gowithbae.platform;

import java.util.UUID;
import vn.gowithbae.api.model.PlaceCard;

/** B owns place visibility and projection; consumers must not query B's tables. */
public interface PlaceQueryService {
    /** Reject missing or non-PUBLISHED places with 404; never silently accept them. */
    void requirePublished(UUID placeId);

    /** Retain unavailable places, returning only their permitted historical card (name + warning). */
    SavedPlace getSavedPlace(UUID placeId);

    record SavedPlace(PlaceCard place, boolean available) {}
}
