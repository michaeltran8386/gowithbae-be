package vn.gowithbae.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.analytics.StatisticsContributor;
import vn.gowithbae.api.model.Platform;
import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.place.PlaceLookupService;

/**
 * Đóng góp chỉ số openPlaceReports (báo sai đang mở NEW/CHECKING) cho ReportsDelegate của Người A.
 * Khi request lọc theo platform, trả null vì place_report không phân tách nền tảng thiết bị (tránh bịa số 0).
 * Lọc khu vực/loại hình lấy danh sách địa điểm qua {@link PlaceLookupService} của phần B, không JOIN bảng place.
 */
@Component
public class PlaceReportStatisticsContributor implements StatisticsContributor {

    private final JdbcClient jdbc;
    private final PlaceLookupService places;

    public PlaceReportStatisticsContributor(JdbcClient jdbc, PlaceLookupService places) {
        this.jdbc = jdbc;
        this.places = places;
    }

    @Override
    public QualityCounts statistics(LocalDate from, LocalDate to, UUID areaId, UUID categoryId, Platform platform) {
        if (platform != null) {
            return new QualityCounts(null, null, null, null);
        }

        OffsetDateTime fromTs = from.atStartOfDay(TimeHelper.VN_ZONE).toOffsetDateTime();
        OffsetDateTime toTs = to.plusDays(1).atStartOfDay(TimeHelper.VN_ZONE).toOffsetDateTime();

        boolean filtered = areaId != null || categoryId != null;
        Set<UUID> placeIds = filtered ? places.idsIn(areaId, categoryId) : Set.of();
        if (filtered && placeIds.isEmpty()) {
            return new QualityCounts(null, null, null, 0L);
        }

        var query = jdbc.sql("""
                SELECT COUNT(*)
                FROM place_report pr
                WHERE pr.status IN ('NEW', 'CHECKING')
                  AND pr.created_at >= :fromTs AND pr.created_at < :toTs
                """ + (filtered ? " AND pr.place_id IN (:placeIds)" : ""))
                .param("fromTs", fromTs)
                .param("toTs", toTs);
        if (filtered) {
            query = query.param("placeIds", placeIds);
        }
        Long openCount = query.query(Long.class).single();

        return new QualityCounts(null, null, null, openCount == null ? 0L : openCount);
    }
}
