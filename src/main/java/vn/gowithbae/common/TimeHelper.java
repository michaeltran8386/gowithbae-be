package vn.gowithbae.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Tiện ích múi giờ Việt Nam (Asia/Ho_Chi_Minh) và đọc timestamptz từ ResultSet. */
public final class TimeHelper {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private TimeHelper() {}

    public static OffsetDateTime getOffsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    public static ZonedDateTime inVietnam(OffsetDateTime odt) {
        return odt.atZoneSameInstant(VN_ZONE);
    }
}
