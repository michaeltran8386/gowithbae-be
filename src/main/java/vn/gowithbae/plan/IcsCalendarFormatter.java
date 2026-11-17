package vn.gowithbae.plan;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import vn.gowithbae.common.TimeHelper;

/** Xuất tệp lịch iCalendar (.ics) một lần theo múi giờ Asia/Ho_Chi_Minh (FR-17, OQ-12). */
@Component
public class IcsCalendarFormatter {

    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    public String formatEvent(
            UUID planId,
            String placeName,
            @Nullable String address,
            OffsetDateTime scheduledAt,
            OffsetDateTime now,
            @Nullable String note,
            boolean cancelled) {

        ZonedDateTime startVn = TimeHelper.inVietnam(scheduledAt);
        ZonedDateTime endVn = startVn.plusHours(2);
        String dtStamp = now.atZoneSameInstant(ZoneOffset.UTC).format(UTC_FMT);

        StringBuilder desc = new StringBuilder();
        if (note != null && !note.isBlank()) {
            desc.append(note.trim()).append("\n\n");
        }
        desc.append("Lịch xuất một lần từ GoWithBae (chưa giữ chỗ tại quán). Nếu đổi giờ hẹn vui lòng tải lại.");

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//GoWithBae//DatePlan 1.0//VI\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("BEGIN:VTIMEZONE\r\n");
        sb.append("TZID:Asia/Ho_Chi_Minh\r\n");
        sb.append("BEGIN:STANDARD\r\n");
        sb.append("DTSTART:19700101T000000\r\n");
        sb.append("TZOFFSETFROM:+0700\r\n");
        sb.append("TZOFFSETTO:+0700\r\n");
        sb.append("TZNAME:+07\r\n");
        sb.append("END:STANDARD\r\n");
        sb.append("END:VTIMEZONE\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:date-plan-").append(planId).append("@gowithbae.vn\r\n");
        sb.append("DTSTAMP:").append(dtStamp).append("\r\n");
        sb.append("DTSTART;TZID=Asia/Ho_Chi_Minh:").append(startVn.format(LOCAL_FMT)).append("\r\n");
        sb.append("DTEND;TZID=Asia/Ho_Chi_Minh:").append(endVn.format(LOCAL_FMT)).append("\r\n");
        sb.append("SUMMARY:").append(escape("Hẹn tại " + placeName)).append("\r\n");
        if (address != null && !address.isBlank()) {
            sb.append("LOCATION:").append(escape(address)).append("\r\n");
        }
        sb.append("DESCRIPTION:").append(escape(desc.toString())).append("\r\n");
        sb.append("STATUS:").append(cancelled ? "CANCELLED" : "CONFIRMED").append("\r\n");
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private String escape(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace(";", "\\;")
                .replace(",", "\\,");
    }
}
