package vn.gowithbae.analytics;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.AdminReportsApiDelegate;
import vn.gowithbae.api.model.*;
import vn.gowithbae.platform.CurrentUser;

@Service
public class ReportsDelegate implements AdminReportsApiDelegate {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final ObjectProvider<StatisticsContributor> contributors;
    public ReportsDelegate(JdbcClient jdbc, CurrentUser current, ObjectProvider<StatisticsContributor> contributors) {
        this.jdbc = jdbc; this.current = current; this.contributors = contributors;
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<ReportSummary> adminReportSummary(LocalDate from, LocalDate to, UUID areaId, UUID categoryId, Platform platform) {
        return ResponseEntity.ok(report(from, to, areaId, categoryId, platform));
    }

    private ReportSummary report(LocalDate from, LocalDate to, UUID area, UUID category, Platform platform) {
        current.requireActive(); current.requireRole("SUPER_ADMIN");
        LocalDate end = to == null ? LocalDate.now(ZONE) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || end.isAfter(LocalDate.now(ZONE)) || end.toEpochDay() - start.toEpochDay() > 365)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report period (maximum 366 days, no future days)");
        String filters = "";
        Map<String, Object> params = new HashMap<>();
        params.put("from", start.atStartOfDay(ZONE).toOffsetDateTime());
        params.put("to", end.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        if (area != null) { filters += " AND props->>'area_id'=:area"; params.put("area", area.toString()); }
        if (category != null) { filters += " AND props->>'category_id'=:category"; params.put("category", category.toString()); }
        if (platform != null) { filters += " AND platform=:platform"; params.put("platform", platform.getValue()); }
        String cte = """
                WITH scoped AS (
                    SELECT *, CASE WHEN user_id IS NOT NULL THEN 'u:'||user_id::text ELSE 'a:'||anonymous_id END AS identity
                    FROM analytics_event WHERE occurred_at <= now()
                """ + filters + """
                ), activity AS (
                    SELECT *,lag(occurred_at) OVER (PARTITION BY identity,session_id,platform ORDER BY occurred_at,event_id) AS previous_at
                    FROM scoped
                ), numbered AS (
                    SELECT *,sum(CASE WHEN previous_at IS NULL OR occurred_at-previous_at >= interval '30 minutes' THEN 1 ELSE 0 END)
                        OVER (PARTITION BY identity,session_id,platform ORDER BY occurred_at,event_id) AS session_number
                    FROM activity
                ), period AS (SELECT * FROM numbered WHERE occurred_at >= :from AND occurred_at < :to),
                sessions AS (
                    SELECT identity,session_id,platform,
                        min(occurred_at) FILTER (WHERE name='search_submitted') AS search_at,
                        min(occurred_at) FILTER (WHERE name='date_plan_created') AS plan_at
                    FROM period GROUP BY identity,session_id,platform,session_number
                ), first_seen AS (
                    SELECT CASE WHEN user_id IS NOT NULL THEN 'u:'||user_id::text ELSE 'a:'||anonymous_id END AS identity,
                        min(occurred_at) AS first_at FROM analytics_event GROUP BY 1
                ), cohort AS (
                    SELECT f.* FROM first_seen f WHERE f.first_at>=:from AND f.first_at<:to
                      AND (f.first_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 30 <
                          (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                      AND EXISTS(SELECT 1 FROM scoped s WHERE s.identity=f.identity AND s.occurred_at=f.first_at)
                )
                """;
        var result = jdbc.sql(cte + """
                SELECT
                  (SELECT count(DISTINCT user_id) FROM period) AS members,
                  (SELECT count(DISTINCT anonymous_id) FROM period WHERE user_id IS NULL) AS anonymous,
                  (SELECT count(*) FROM period WHERE name='search_submitted') AS searches,
                  (SELECT count(*) FROM period WHERE name='place_viewed') AS views,
                  (SELECT count(*) FROM period WHERE name='place_saved') AS saves,
                  (SELECT count(*) FROM period s WHERE s.name='search_submitted' AND EXISTS(
                      SELECT 1 FROM period r WHERE r.name='results_shown' AND r.identity=s.identity
                      AND r.session_id=s.session_id AND r.platform=s.platform AND r.session_number=s.session_number AND r.occurred_at>=s.occurred_at
                      AND r.props->>'search_id'=s.props->>'search_id' AND r.props->>'results_count'='0')) AS zeroes,
                  (SELECT count(*) FROM period s WHERE s.name='search_submitted' AND EXISTS(
                      SELECT 1 FROM period r WHERE r.name='results_shown' AND r.identity=s.identity
                      AND r.session_id=s.session_id AND r.platform=s.platform AND r.session_number=s.session_number AND r.occurred_at>=s.occurred_at
                      AND r.props->>'search_id'=s.props->>'search_id' AND jsonb_exists(r.props,'results_count'))) AS measured,
                  (SELECT count(*) FROM sessions WHERE search_at IS NOT NULL) AS searched_sessions,
                  (SELECT count(*) FROM sessions WHERE plan_at>=search_at) AS converted,
                  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (plan_at-search_at))/60.0)
                      FROM sessions WHERE plan_at>=search_at) AS median,
                  (SELECT count(*) FROM cohort) AS eligible,
                  (SELECT count(*) FROM cohort c WHERE EXISTS(SELECT 1 FROM scoped s WHERE s.identity=c.identity
                      AND (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN
                          (c.first_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+1 AND
                          (c.first_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+30)) AS returned
                """).params(params).query((rs, n) -> {
            long searches = rs.getLong("searches"), measured = rs.getLong("measured");
            ReportSummary r = new ReportSummary(OffsetDateTime.now(ZoneOffset.UTC))
                    .activeMembers(metric((double) rs.getLong("members"))).activeAnonymous(metric((double) rs.getLong("anonymous")))
                    .searches(metric((double) searches)).placeViews(metric((double) rs.getLong("views")))
                    .saves(metric((double) rs.getLong("saves")))
                    .zeroResultRate(measured != searches ? unavailable("Incomplete search/result correlation") : ratio(rs.getLong("zeroes"), searches))
                    .searchToPlanRate(ratio(rs.getLong("converted"), rs.getLong("searched_sessions")))
                    .medianMinutesToPlan(rs.getObject("median") == null ? unavailable("No valid search-to-plan sessions") : metric(rs.getDouble("median")))
                    .retention30d(rs.getLong("eligible") == 0 ? unavailable("No cohort with a complete 30-day window") : ratio(rs.getLong("returned"), rs.getLong("eligible")));
            return r;
        }).single();
        Long[] quality = new Long[4];
        contributors.orderedStream().forEach(provider -> {
            var counts = provider.statistics(start, end, area, category, platform);
            if (counts == null) return;
            Long[] values = { counts.placesMissingPrice(), counts.placesMissingHours(),
                    counts.placesVerificationOverdue(), counts.openPlaceReports() };
            for (int i = 0; i < values.length; i++) if (values[i] != null) {
                if (values[i] < 0) throw new IllegalStateException("Statistics counts must be nonnegative");
                quality[i] = Math.addExact(quality[i] == null ? 0L : quality[i], values[i]);
            }
        });
        return result.placesMissingPrice(qualityMetric(quality[0])).placesMissingHours(qualityMetric(quality[1]))
                .placesVerificationOverdue(qualityMetric(quality[2])).openPlaceReports(qualityMetric(quality[3]));
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<String> adminExportReport(LocalDate from, LocalDate to, UUID area, UUID category, Platform platform) {
        ReportSummary r = report(from, to, area, category, platform);
        StringBuilder csv = new StringBuilder("metric,value,note\r\n");
        row(csv, "activeMembers", r.getActiveMembers()); row(csv, "activeAnonymous", r.getActiveAnonymous());
        row(csv, "searches", r.getSearches()); row(csv, "zeroResultRate", r.getZeroResultRate());
        row(csv, "placeViews", r.getPlaceViews()); row(csv, "saves", r.getSaves());
        row(csv, "searchToPlanRate", r.getSearchToPlanRate()); row(csv, "medianMinutesToPlan", r.getMedianMinutesToPlan());
        row(csv, "retention30d", r.getRetention30d()); row(csv, "placesMissingPrice", r.getPlacesMissingPrice());
        row(csv, "placesMissingHours", r.getPlacesMissingHours()); row(csv, "placesVerificationOverdue", r.getPlacesVerificationOverdue());
        row(csv, "openPlaceReports", r.getOpenPlaceReports());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.csv").body(csv.toString());
    }
    private static MetricValue metric(Double value) { return new MetricValue().value(value); }
    private static MetricValue qualityMetric(Long value) {
        return value == null ? unavailable("Statistics provider unavailable") : metric(value.doubleValue());
    }
    private static MetricValue unavailable(String note) { return new MetricValue().value(null).note(note); }
    private static MetricValue ratio(long numerator, long denominator) {
        return denominator == 0 ? unavailable("No denominator") : metric((double) numerator / denominator);
    }
    private static void row(StringBuilder csv, String name, MetricValue value) {
        csv.append(cell(name)).append(',').append(cell(value.getValue() == null ? "" : value.getValue().toString()))
                .append(',').append(cell(value.getNote())).append("\r\n");
    }
    static String cell(String value) {
        if (value == null) return "\"\"";
        String trimmed = value.stripLeading();
        if (!trimmed.isEmpty() && ("=+-@".indexOf(trimmed.charAt(0)) >= 0 || value.charAt(0) == '\t' || value.charAt(0) == '\r')) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
