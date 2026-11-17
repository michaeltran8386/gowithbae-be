package vn.gowithbae.place;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tính lại {@code place.search_text} = tên + khu vực + nhãn đang dùng, bỏ dấu, chữ thường (FR-02).
 * Gọi sau mỗi lần sửa tên/khu vực/nhãn của địa điểm, hoặc đổi tên nhãn/khu vực.
 */
@Component
public class SearchTextUpdater {

    private final JdbcClient jdbc;

    public SearchTextUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void refresh(Collection<UUID> placeIds) {
        if (placeIds.isEmpty()) {
            return;
        }
        Map<UUID, String> raw = new HashMap<>();
        jdbc.sql("""
                SELECT p.id, concat_ws(' ', p.name, a.name,
                       (SELECT string_agg(t.name, ' ' ORDER BY t.group_code, t.code)
                        FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
                        WHERE pt.place_id = p.id AND t.status = 'ACTIVE')) AS text
                FROM place p LEFT JOIN area a ON a.id = p.area_id
                WHERE p.id IN (:ids)
                """)
                .param("ids", placeIds)
                .query(rs -> {
                    raw.put(rs.getObject("id", UUID.class), rs.getString("text"));
                });
        raw.forEach((id, text) -> jdbc.sql("UPDATE place SET search_text = :t WHERE id = :id")
                .param("t", PlacesDelegate.normalize(text))
                .param("id", id)
                .update());
    }
}
