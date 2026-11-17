package vn.gowithbae.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.gowithbae.common.TimeHelper;
import vn.gowithbae.shared.JdbcPlaceQueryService.ExceptionRow;
import vn.gowithbae.shared.JdbcPlaceQueryService.TimeWindow;
import vn.gowithbae.shared.PlaceQueryService.OpeningStatus;

class OpeningHoursEvaluationTest {

    @Test
    void closedDateExceptionOverridesOpenWeeklySchedule() {
        ZonedDateTime target = ZonedDateTime.of(2026, 12, 25, 19, 0, 0, 0, TimeHelper.VN_ZONE);
        OpeningStatus status = JdbcPlaceQueryService.evaluateOpeningRules(
                "KNOWN",
                target,
                List.of(new ExceptionRow(LocalDate.of(2026, 12, 25), true, null, null)),
                List.of(),
                List.of(new TimeWindow(LocalTime.of(9, 0), LocalTime.of(22, 0))));

        assertThat(status).isEqualTo(OpeningStatus.CLOSED);
    }

    @Test
    void overnightShiftFromPreviousDayKeepsEarlyMorningOpen() {
        ZonedDateTime saturday1am = ZonedDateTime.of(2026, 12, 26, 1, 0, 0, 0, TimeHelper.VN_ZONE);
        OpeningStatus status = JdbcPlaceQueryService.evaluateOpeningRules(
                "KNOWN",
                saturday1am,
                List.of(),
                List.of(new TimeWindow(LocalTime.of(18, 0), LocalTime.of(2, 0))),
                List.of(new TimeWindow(LocalTime.of(18, 0), LocalTime.of(2, 0))));

        assertThat(status).isEqualTo(OpeningStatus.OPEN);
    }

    @Test
    void unknownHoursReturnsUnknownWhenNoExceptionBlocksDay() {
        ZonedDateTime target = ZonedDateTime.of(2026, 12, 25, 19, 0, 0, 0, TimeHelper.VN_ZONE);
        OpeningStatus status = JdbcPlaceQueryService.evaluateOpeningRules(
                "UNKNOWN",
                target,
                List.of(),
                List.of(),
                List.of());

        assertThat(status).isEqualTo(OpeningStatus.UNKNOWN);
    }
}
