package vn.gowithbae.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IcsCalendarFormatterTest {

    private final IcsCalendarFormatter formatter = new IcsCalendarFormatter();

    @Test
    void formatsVietnamTimezoneAndEscapesSpecialCharacters() {
        UUID planId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        // 12:30Z == 19:30 Asia/Ho_Chi_Minh (+07:00)
        OffsetDateTime scheduled = OffsetDateTime.of(2026, 12, 24, 12, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.of(2026, 12, 20, 8, 0, 0, 0, ZoneOffset.UTC);

        String ics = formatter.formatEvent(
                planId,
                "Quán Cà Phê, Sân Vườn; Đẹp",
                "123 Lê Lợi, Q1",
                scheduled,
                now,
                "Nhớ mang máy ảnh\nTầng 2",
                false);

        assertThat(ics).contains("TZID:Asia/Ho_Chi_Minh\r\n");
        assertThat(ics).contains("DTSTART;TZID=Asia/Ho_Chi_Minh:20261224T193000\r\n");
        assertThat(ics).contains("DTEND;TZID=Asia/Ho_Chi_Minh:20261224T213000\r\n");
        assertThat(ics).contains("SUMMARY:Hẹn tại Quán Cà Phê\\, Sân Vườn\\; Đẹp\r\n");
        assertThat(ics).contains("LOCATION:123 Lê Lợi\\, Q1\r\n");
        assertThat(ics).contains("Nhớ mang máy ảnh\\nTầng 2");
        assertThat(ics).contains("STATUS:CONFIRMED\r\n");
    }

    @Test
    void marksCancelledStatusWhenPlanIsCancelled() {
        String ics = formatter.formatEvent(
                UUID.randomUUID(),
                "Bistro",
                "Q3",
                OffsetDateTime.parse("2026-12-25T19:00:00+07:00"),
                OffsetDateTime.parse("2026-12-20T10:00:00+07:00"),
                null,
                true);

        assertThat(ics).contains("STATUS:CANCELLED\r\n");
    }
}
