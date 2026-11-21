package vn.gowithbae.place;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.gowithbae.analytics.StatisticsContributor;
import vn.gowithbae.api.model.Platform;
import vn.gowithbae.common.TimeHelper;

/**
 * Chỉ số chất lượng dữ liệu địa điểm cho báo cáo của Người A (SRS §10: thiếu giá, thiếu giờ, quá hạn xác minh).
 *
 * <ul>
 *   <li>Đếm trên địa điểm <b>đang hiển thị</b> tại thời điểm xem báo cáo (ảnh chụp hiện tại), nên không phụ thuộc kỳ báo cáo.</li>
 *   <li>Thiếu giá = chưa có dòng giá hoặc giá "chưa rõ"; thiếu giờ = giờ chưa ở trạng thái "đã biết".</li>
 *   <li>Quá hạn xác minh = chưa có ngày kiểm tra hoặc ngày kiểm tra cũ hơn ngưỡng cấu hình (OQ-20 chưa chốt; mặc định 180 ngày).</li>
 *   <li>Lọc theo nền tảng thì trả null: dữ liệu địa điểm không gắn nền tảng (tránh bịa số).</li>
 * </ul>
 * openPlaceReports do {@code PlaceReportStatisticsContributor} (phần C) cung cấp, ở đây để null.
 */
@Component
public class PlaceStatisticsContributor implements StatisticsContributor {

    private final JdbcClient jdbc;
    private final int verificationMaxAgeDays;

    public PlaceStatisticsContributor(JdbcClient jdbc,
            @Value("${app.places.verification-max-age-days:180}") int verificationMaxAgeDays) {
        if (verificationMaxAgeDays < 1) {
            throw new IllegalStateException("app.places.verification-max-age-days phải ≥ 1");
        }
        this.jdbc = jdbc;
        this.verificationMaxAgeDays = verificationMaxAgeDays;
    }

    @Override
    public QualityCounts statistics(LocalDate from, LocalDate to, UUID areaId, UUID categoryId, Platform platform) {
        if (platform != null) {
            return new QualityCounts(null, null, null, null);
        }
        LocalDate overdueBefore = LocalDate.now(TimeHelper.VN_ZONE).minusDays(verificationMaxAgeDays);
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE pp.place_id IS NULL OR pp.status <> 'KNOWN') AS missing_price,
                       count(*) FILTER (WHERE p.hours_status IS DISTINCT FROM 'KNOWN') AS missing_hours,
                       count(*) FILTER (WHERE p.verified_at IS NULL OR p.verified_at < :overdueBefore) AS overdue
                FROM place p
                LEFT JOIN place_price pp ON pp.place_id = p.id
                WHERE p.status = 'PUBLISHED'
                  AND (CAST(:areaId AS uuid) IS NULL OR p.area_id = CAST(:areaId AS uuid))
                  AND (CAST(:categoryId AS uuid) IS NULL OR p.category_id = CAST(:categoryId AS uuid))
                """)
                .param("overdueBefore", overdueBefore)
                .param("areaId", areaId)
                .param("categoryId", categoryId)
                .query((rs, i) -> new QualityCounts(rs.getLong("missing_price"), rs.getLong("missing_hours"), rs.getLong("overdue"), null))
                .single();
    }
}
