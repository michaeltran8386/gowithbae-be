package vn.gowithbae.place;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.common.files.FileUrls;

/**
 * SQL dùng chung để dựng {@link PlaceCard}: tìm kiếm, bộ sưu tập, so sánh, quản trị.
 * Câu truy vấn gọi: {@code SELECT } + {@link #CARD_COLUMNS} + {@link #CARD_FROM} + điều kiện riêng.
 */
@Component
public class PlaceReader {

    public static final String CARD_COLUMNS = """
            p.id, p.name, p.lat, p.lng, a.name AS area_name,
            coalesce(pp.status, 'UNKNOWN') AS price_status, pp.standard_for_two, pp.min_price, pp.max_price,
            pp.includes, pp.verified_at AS price_verified_at, cover_file.storage_key AS cover_key
            """;

    /** Alias: p = place, pp = place_price, a = area, cover = ảnh bìa, cover_file = tệp ảnh bìa. */
    public static final String CARD_FROM = """
             FROM place p
             LEFT JOIN place_price pp ON pp.place_id = p.id
             LEFT JOIN area a ON a.id = p.area_id
             LEFT JOIN place_image cover ON cover.place_id = p.id AND cover.is_cover
             LEFT JOIN stored_file cover_file ON cover_file.id = cover.file_id
            """;

    private final JdbcClient jdbc;
    private final FileUrls fileUrls;

    public PlaceReader(JdbcClient jdbc, FileUrls fileUrls) {
        this.jdbc = jdbc;
        this.fileUrls = fileUrls;
    }

    /** Map một dòng có đủ {@link #CARD_COLUMNS}; nhãn gắn sau bằng {@link #attachTags}. */
    public PlaceCard mapCard(ResultSet rs) throws SQLException {
        return new PlaceCard(rs.getObject("id", UUID.class), rs.getString("name"), mapPrice(rs), new ArrayList<>())
                .areaName(rs.getString("area_name"))
                .coverUrl(fileUrls.publicUrl(rs.getString("cover_key")))
                .lat(rs.getDouble("lat"))
                .lng(rs.getDouble("lng"));
    }

    /** Giá chưa biết trả status UNKNOWN, các số để null (RULE-04). */
    public static Price mapPrice(ResultSet rs) throws SQLException {
        return new Price(KnownStatus.fromValue(rs.getString("price_status")))
                .standardForTwo(rs.getObject("standard_for_two", Long.class))
                .min(rs.getObject("min_price", Long.class))
                .max(rs.getObject("max_price", Long.class))
                .includes(rs.getString("includes"))
                .verifiedAt(rs.getObject("price_verified_at", LocalDate.class));
    }

    /** Tối đa 3 nhãn đang dùng trên thẻ, không gồm loại hình (FR-02). */
    public void attachTags(List<PlaceCard> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<UUID, PlaceCard> byId = items.stream()
                .collect(Collectors.toMap(PlaceCard::getId, Function.identity(), (x, y) -> x));
        jdbc.sql("""
                SELECT place_id, id, group_code, code, name FROM (
                  SELECT pt.place_id, t.id, t.group_code, t.code, t.name,
                         row_number() OVER (PARTITION BY pt.place_id ORDER BY t.group_code, t.code) AS rn
                  FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
                  WHERE pt.place_id IN (:ids) AND t.status = 'ACTIVE' AND t.group_code <> 'CATEGORY'
                ) x WHERE rn <= 3
                """)
                .param("ids", byId.keySet())
                .query(rs -> {
                    byId.get(rs.getObject("place_id", UUID.class)).addTagsItem(new Taxonomy(
                            rs.getObject("id", UUID.class),
                            TaxonomyGroup.fromValue(rs.getString("group_code")),
                            rs.getString("code"),
                            rs.getString("name"),
                            ActiveStatus.ACTIVE));
                });
    }
}
