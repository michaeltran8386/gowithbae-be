package vn.gowithbae.analytics;

import java.time.LocalDate;
import java.util.UUID;
import vn.gowithbae.api.model.Platform;

/** B/C provide quality counts for the same report scope without exposing their tables to A. */
public interface StatisticsContributor {
    QualityCounts statistics(LocalDate from, LocalDate to, UUID areaId, UUID categoryId, Platform platform);

    /** Null means unavailable; known nonnegative counts from disjoint providers are added per field. */
    record QualityCounts(Long placesMissingPrice, Long placesMissingHours,
            Long placesVerificationOverdue, Long openPlaceReports) {}
}
