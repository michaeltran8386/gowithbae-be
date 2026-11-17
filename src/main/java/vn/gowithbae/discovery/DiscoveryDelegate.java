package vn.gowithbae.discovery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.DiscoveryApiDelegate;
import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.Area;
import vn.gowithbae.api.model.CollectionDetail;
import vn.gowithbae.api.model.CollectionSummary;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.common.files.FileUrls;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.place.PlaceReader;

/** Khám phá: bộ sưu tập, nhãn lọc, khu vực (FR-01, FR-03, FR-26). Dữ liệu công khai, không cần đăng nhập. */
@Service
public class DiscoveryDelegate implements DiscoveryApiDelegate {

    /** Bộ sưu tập đang trong khung hiển thị; null ở một đầu = không giới hạn đầu đó. */
    private static final String VISIBLE_NOW = """
            (c.visible_from IS NULL OR c.visible_from <= now())
            AND (c.visible_until IS NULL OR c.visible_until > now())
            """;

    private final JdbcClient jdbc;
    private final PlaceReader placeReader;
    private final FileUrls fileUrls;

    public DiscoveryDelegate(JdbcClient jdbc, PlaceReader placeReader, FileUrls fileUrls) {
        this.jdbc = jdbc;
        this.placeReader = placeReader;
        this.fileUrls = fileUrls;
    }

    @Override
    public ResponseEntity<List<Taxonomy>> listTaxonomies(TaxonomyGroup group) {
        var rows = jdbc.sql("""
                SELECT id, group_code, code, name FROM taxonomy
                WHERE status = 'ACTIVE' AND (CAST(:group AS text) IS NULL OR group_code = CAST(:group AS text))
                ORDER BY group_code, code
                """)
                .param("group", group == null ? null : group.getValue())
                .query((rs, i) -> new Taxonomy(
                        rs.getObject("id", UUID.class),
                        TaxonomyGroup.fromValue(rs.getString("group_code")),
                        rs.getString("code"),
                        rs.getString("name"),
                        ActiveStatus.ACTIVE))
                .list();
        return ResponseEntity.ok(rows);
    }

    /** shortcut: OQ-01 chưa chốt danh mục khu vực nên DB chưa seed; trả danh sách rỗng cho tới khi quản trị tạo. */
    @Override
    public ResponseEntity<List<Area>> listAreas() {
        var rows = jdbc.sql("SELECT id, code, name FROM area WHERE status = 'ACTIVE' ORDER BY name")
                .query((rs, i) -> new Area(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        ActiveStatus.ACTIVE))
                .list();
        return ResponseEntity.ok(rows);
    }

    @Override
    public ResponseEntity<List<CollectionSummary>> listCollections() {
        var rows = jdbc.sql("""
                SELECT c.id, c.title, f.storage_key AS cover_key
                FROM collection c LEFT JOIN stored_file f ON f.id = c.cover_file_id
                WHERE """ + VISIBLE_NOW + """
                ORDER BY coalesce(c.visible_from, c.created_at) DESC, c.id
                """)
                .query((rs, i) -> new CollectionSummary(rs.getObject("id", UUID.class), rs.getString("title"))
                        .coverUrl(fileUrls.publicUrl(rs.getString("cover_key"))))
                .list();
        return ResponseEntity.ok(rows);
    }

    @Override
    public ResponseEntity<CollectionDetail> getCollection(UUID collectionId) {
        // Ngoài khung hiển thị coi như không tồn tại với người dùng.
        CollectionDetail detail = jdbc.sql("""
                SELECT c.id, c.title, c.body, c.visible_from, c.visible_until, f.storage_key AS cover_key
                FROM collection c LEFT JOIN stored_file f ON f.id = c.cover_file_id
                WHERE c.id = :id AND """ + VISIBLE_NOW)
                .param("id", collectionId)
                .query((rs, i) -> new CollectionDetail(rs.getObject("id", UUID.class), rs.getString("title"))
                        .coverUrl(fileUrls.publicUrl(rs.getString("cover_key")))
                        .body(rs.getString("body"))
                        .visibleFrom(rs.getObject("visible_from", OffsetDateTime.class))
                        .visibleUntil(rs.getObject("visible_until", OffsetDateTime.class)))
                .optional()
                .orElseThrow(Problems::notFound);

        // Chỉ địa điểm đang công khai (RULE-01); nơi bị ẩn sau khi thêm vào bộ sưu tập tự biến mất.
        List<PlaceCard> places = jdbc.sql("SELECT " + PlaceReader.CARD_COLUMNS + PlaceReader.CARD_FROM + """
                 JOIN collection_item ci ON ci.place_id = p.id
                 WHERE ci.collection_id = :id AND p.status = 'PUBLISHED'
                 ORDER BY ci.sort_order, p.code
                """)
                .param("id", collectionId)
                .query((rs, i) -> placeReader.mapCard(rs))
                .list();
        placeReader.attachTags(places);

        return ResponseEntity.ok(detail.places(places));
    }
}
