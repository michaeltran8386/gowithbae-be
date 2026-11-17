package vn.gowithbae.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.analytics.StatisticsContributor;
import vn.gowithbae.api.model.Platform;
import vn.gowithbae.common.TimeHelper;

/**
 * Đóng góp chỉ số openPlaceReports (báo sai đang mở NEW/CHECKING) cho ReportsDelegate của Người A.
 * Khi request lọc theo platform, trả null vì place_report không phân tách nền tảng thiết bị (tránh bịa số 0).
 */
@Component
public class PlaceReportStatisticsContributor implements StatisticsContributor {

    private final JdbcClient jdbc;

    public PlaceReportStatisticsContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public QualityCounts statistics(LocalDate from, LocalDate to, UUID areaId, UUID categoryId, Platform platform) {
        if (platform != null) {
            return new QualityCounts(null, null, null, null);
        }

        OffsetDateTime fromTs = from.atStartOfDay(TimeHelper.VN_ZONE).toOffsetDateTime();
        OffsetDateTime toTs = to.plusDays(1).atStartOfDay(TimeHelper.VN_ZONE).toOffsetDateTime();

        Long openCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM place_report pr
                JOIN place p ON p.id = pr.place_id
                WHERE pr.status IN ('NEW', 'CHECKING')
                  AND pr.created_at >= :fromTs AND pr.created_at < :toTs
                  AND (CAST(:areaId AS uuid) IS NULL OR p.area_id = CAST(:areaId AS uuid))
                  AND (CAST(:categoryId AS uuid) IS NULL OR p.category_id = CAST(:categoryId AS uuid))
                """)
                .param("fromTs", fromTs)
                .param("toTs", toTs)
                .param("areaId", areaId)
                .param("categoryId", categoryId)
                .query(Long.class)
                .single();

        return new QualityCounts(null, null, null, openCount == null ? 0L : openCount);
    }
}
