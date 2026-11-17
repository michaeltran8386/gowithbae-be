package vn.gowithbae.admin.imports;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.api.model.AdminPlaceWrite;
import vn.gowithbae.api.model.AdminPlaceWritePrice;
import vn.gowithbae.api.model.KnownStatus;

/**
 * Kiểm tra từng dòng CSV mẫu v1 và đổi thành {@link AdminPlaceWrite}. Tra cứu danh mục một lần cho cả lô.
 * Mọi lỗi của dòng được gom lại để người nhập sửa một lần (RULE-23: không âm thầm bỏ dòng lỗi).
 */
@Component
public class ImportRowValidator {

    public static final String TEMPLATE_VERSION = "v1";

    /** Thứ tự cột trong file mẫu; file nhập được phép đổi thứ tự cột nhưng phải đủ cột. */
    public static final List<String> COLUMNS = List.of(
            "code", "name", "category_code", "area_code", "address", "lat", "lng",
            "description", "phone", "website", "tag_codes",
            "price_status", "standard_for_two", "min_price", "max_price", "price_includes", "price_source",
            "source", "verified_at");

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{3,40}$");

    /** Kết quả một dòng: lỗi (rỗng = hợp lệ) và dữ liệu ghi được khi hợp lệ. */
    public record RowResult(List<String> errors, AdminPlaceWrite write) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Danh mục tra cứu một lần cho cả lô. */
    public final class Lookup {
        private final Map<String, UUID> categories = new HashMap<>();
        private final Map<String, UUID> areas = new HashMap<>();
        /** Theo mã nhãn: danh sách id (một mã có thể có ở nhiều nhóm) và theo "NHÓM:MÃ". */
        private final Map<String, List<UUID>> tagsByCode = new HashMap<>();
        private final Map<String, UUID> tagsByGroupCode = new HashMap<>();

        private Lookup() {
            jdbc.sql("SELECT id, group_code, code FROM taxonomy WHERE status = 'ACTIVE'").query(rs -> {
                UUID id = rs.getObject("id", UUID.class);
                String group = rs.getString("group_code");
                String code = rs.getString("code");
                if ("CATEGORY".equals(group)) {
                    categories.put(code, id);
                } else {
                    tagsByCode.computeIfAbsent(code, k -> new ArrayList<>()).add(id);
                    tagsByGroupCode.put(group + ":" + code, id);
                }
            });
            jdbc.sql("SELECT id, code FROM area WHERE status = 'ACTIVE'")
                    .query(rs -> {
                        areas.put(rs.getString("code"), rs.getObject("id", UUID.class));
                    });
        }
    }

    private final JdbcClient jdbc;

    public ImportRowValidator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Lookup lookup() {
        return new Lookup();
    }

    /**
     * @param raw     giá trị theo tên cột (đã trim)
     * @param dupLine dòng trước trong cùng file có cùng mã, hoặc null
     */
    public RowResult validate(Map<String, String> raw, Lookup lookup, Integer dupLine) {
        List<String> errors = new ArrayList<>();

        String code = raw.get("code");
        if (code.isEmpty()) {
            errors.add("Thiếu mã chi nhánh (code)");
        } else if (!CODE.matcher(code).matches()) {
            errors.add("Mã chi nhánh chỉ gồm A–Z, 0–9, _ hoặc -, dài 3–40 ký tự");
        } else if (dupLine != null) {
            errors.add("Mã chi nhánh trùng với dòng " + dupLine);
        } else if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM place WHERE code = :c)").param("c", code)
                .query(Boolean.class).single()) {
            errors.add("Mã chi nhánh đã tồn tại trong hệ thống");
        }

        String name = required(raw, "name", "tên", 150, errors);
        String address = required(raw, "address", "địa chỉ", 300, errors);
        maxLength(raw, "description", 5000, errors);
        maxLength(raw, "phone", 20, errors);

        UUID categoryId = null;
        if (raw.get("category_code").isEmpty()) {
            errors.add("Thiếu loại hình (category_code)");
        } else {
            categoryId = lookup.categories.get(raw.get("category_code"));
            if (categoryId == null) {
                errors.add("Loại hình không tồn tại hoặc đã ngừng dùng: " + raw.get("category_code"));
            }
        }
        UUID areaId = null;
        if (raw.get("area_code").isEmpty()) {
            errors.add("Thiếu khu vực (area_code)");
        } else {
            areaId = lookup.areas.get(raw.get("area_code"));
            if (areaId == null) {
                errors.add("Khu vực không tồn tại hoặc đã ngừng dùng: " + raw.get("area_code"));
            }
        }

        Double lat = coordinate(raw, "lat", 90, errors);
        Double lng = coordinate(raw, "lng", 180, errors);

        URI website = null;
        String site = raw.get("website");
        if (!site.isEmpty()) {
            if (!site.startsWith("http://") && !site.startsWith("https://")) {
                errors.add("Website phải bắt đầu bằng http:// hoặc https://");
            } else {
                try {
                    website = URI.create(site);
                } catch (IllegalArgumentException e) {
                    errors.add("Website không hợp lệ");
                }
            }
        }

        List<UUID> tagIds = new ArrayList<>();
        for (String token : raw.get("tag_codes").split(";")) {
            String tag = token.trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.contains(":")) {
                UUID id = lookup.tagsByGroupCode.get(tag);
                if (id == null) {
                    errors.add("Nhãn không tồn tại hoặc đã ngừng dùng: " + tag);
                } else {
                    tagIds.add(id);
                }
                continue;
            }
            List<UUID> ids = lookup.tagsByCode.getOrDefault(tag, List.of());
            if (ids.isEmpty()) {
                errors.add("Nhãn không tồn tại hoặc đã ngừng dùng: " + tag);
            } else if (ids.size() > 1) {
                errors.add("Mã nhãn " + tag + " có ở nhiều nhóm, ghi dạng NHÓM:MÃ");
            } else {
                tagIds.add(ids.getFirst());
            }
        }

        AdminPlaceWritePrice price = price(raw, errors);
        LocalDate verifiedAt = date(raw, "verified_at", errors);

        if (!errors.isEmpty()) {
            return new RowResult(errors, null);
        }
        AdminPlaceWrite write = new AdminPlaceWrite(code, name, categoryId, areaId, address, lat, lng)
                .description(emptyToNull(raw.get("description")))
                .phone(emptyToNull(raw.get("phone")))
                .website(website)
                .tagIds(tagIds)
                .price(price)
                .source(emptyToNull(raw.get("source")))
                .verifiedAt(verifiedAt);
        return new RowResult(errors, write);
    }

    /** price_status trống = chưa ghi giá; KNOWN cần đủ ba số và 0 ≤ min ≤ chuẩn ≤ max (RULE-03). */
    private AdminPlaceWritePrice price(Map<String, String> raw, List<String> errors) {
        String status = raw.get("price_status");
        if (status.isEmpty()) {
            return null;
        }
        if (!status.equals("KNOWN") && !status.equals("UNKNOWN")) {
            errors.add("price_status chỉ nhận KNOWN hoặc UNKNOWN");
            return null;
        }
        var price = new AdminPlaceWritePrice(KnownStatus.fromValue(status))
                .includes(emptyToNull(raw.get("price_includes")))
                .source(emptyToNull(raw.get("price_source")));
        if (status.equals("UNKNOWN")) {
            return price;
        }
        Long std = money(raw, "standard_for_two", errors);
        Long min = money(raw, "min_price", errors);
        Long max = money(raw, "max_price", errors);
        if (std == null || min == null || max == null) {
            errors.add("Giá đã biết cần đủ standard_for_two, min_price, max_price");
        } else if (min < 0 || min > std || std > max) {
            errors.add("Giá cần 0 ≤ min_price ≤ standard_for_two ≤ max_price");
        }
        return price.standardForTwo(std).min(min).max(max);
    }

    private static String required(Map<String, String> raw, String column, String label, int max, List<String> errors) {
        String v = raw.get(column);
        if (v.isEmpty()) {
            errors.add("Thiếu " + label + " (" + column + ")");
            return null;
        }
        maxLength(raw, column, max, errors);
        return v;
    }

    private static void maxLength(Map<String, String> raw, String column, int max, List<String> errors) {
        if (raw.get(column).length() > max) {
            errors.add(column + " dài quá " + max + " ký tự");
        }
    }

    private static Double coordinate(Map<String, String> raw, String column, double limit, List<String> errors) {
        String v = raw.get(column);
        if (v.isEmpty()) {
            errors.add("Thiếu tọa độ " + column);
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || d < -limit || d > limit) {
                errors.add("Tọa độ " + column + " phải trong khoảng ±" + (int) limit);
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            errors.add("Tọa độ " + column + " không phải số (dùng dấu chấm thập phân)");
            return null;
        }
    }

    private static Long money(Map<String, String> raw, String column, List<String> errors) {
        String v = raw.get(column);
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            errors.add(column + " phải là số nguyên VND, không có dấu phân cách");
            return null;
        }
    }

    private static LocalDate date(Map<String, String> raw, String column, List<String> errors) {
        String v = raw.get(column);
        if (v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            errors.add(column + " phải có dạng YYYY-MM-DD");
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
