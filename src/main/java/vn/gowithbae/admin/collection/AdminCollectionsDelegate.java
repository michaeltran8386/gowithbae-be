package vn.gowithbae.admin.collection;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.gowithbae.admin.AdminPermissions;
import vn.gowithbae.api.AdminCollectionsApiDelegate;
import vn.gowithbae.api.model.CollectionDetail;
import vn.gowithbae.api.model.CollectionWrite;
import vn.gowithbae.common.audit.AuditService;
import vn.gowithbae.common.files.FileUrls;
import vn.gowithbae.common.security.CurrentUser;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.place.PlaceReader;

/**
 * Biên tập bộ sưu tập khám phá (FR-26). Quản trị thấy mọi bộ sưu tập và mọi địa điểm đã gắn, kể cả nơi chưa công khai;
 * trang công khai tự lọc theo khung hiển thị và RULE-01 (xem DiscoveryDelegate).
 * shortcut: OQ-18 — quyền biên tập bộ sưu tập tạm theo nhóm biên tập nội dung.
 */
@Service
public class AdminCollectionsDelegate implements AdminCollectionsApiDelegate {

    private final JdbcClient jdbc;
    private final PlaceReader placeReader;
    private final FileUrls fileUrls;
    private final AuditService audit;

    public AdminCollectionsDelegate(JdbcClient jdbc, PlaceReader placeReader, FileUrls fileUrls, AuditService audit) {
        this.jdbc = jdbc;
        this.placeReader = placeReader;
        this.fileUrls = fileUrls;
        this.audit = audit;
    }

    @Override
    public ResponseEntity<List<CollectionDetail>> adminListCollections() {
        AdminPermissions.requireContentRead();
        List<UUID> ids = jdbc.sql("SELECT id FROM collection ORDER BY updated_at DESC, id").query(UUID.class).list();
        return ResponseEntity.ok(load(ids));
    }

    @Override
    @Transactional
    public ResponseEntity<CollectionDetail> adminCreateCollection(CollectionWrite body) {
        AdminPermissions.requireContentEdit();
        validate(body);
        UUID id = jdbc.sql("""
                INSERT INTO collection (title, body, cover_file_id, visible_from, visible_until, created_by)
                VALUES (:title, :body, :cover, :from, :until, :actor) RETURNING id
                """)
                .params(params(body))
                .param("actor", CurrentUser.id())
                .query(UUID.class)
                .single();
        writeItems(id, body.getPlaceIds());
        audit.log(CurrentUser.id(), "COLLECTION_CREATE", "collection", id, null, summary(id), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(load(List.of(id)).getFirst());
    }

    @Override
    @Transactional
    public ResponseEntity<CollectionDetail> adminUpdateCollection(UUID collectionId, CollectionWrite body) {
        AdminPermissions.requireContentEdit();
        Map<String, Object> before = summary(collectionId);
        validate(body);
        jdbc.sql("""
                UPDATE collection SET title = :title, body = :body, cover_file_id = :cover,
                       visible_from = :from, visible_until = :until, updated_at = now()
                WHERE id = :id
                """)
                .params(params(body))
                .param("id", collectionId)
                .update();
        writeItems(collectionId, body.getPlaceIds());
        audit.log(CurrentUser.id(), "COLLECTION_UPDATE", "collection", collectionId, before, summary(collectionId), null);
        return ResponseEntity.ok(load(List.of(collectionId)).getFirst());
    }

    /** Bộ sưu tập không bị tham chiếu ở nơi khác nên xóa hẳn; item tự xóa theo ON DELETE CASCADE. */
    @Override
    @Transactional
    public ResponseEntity<Void> adminDeleteCollection(UUID collectionId) {
        AdminPermissions.requireContentEdit();
        Map<String, Object> before = summary(collectionId);
        jdbc.sql("DELETE FROM collection WHERE id = :id").param("id", collectionId).update();
        audit.log(CurrentUser.id(), "COLLECTION_DELETE", "collection", collectionId, before, null, null);
        return ResponseEntity.noContent().build();
    }

    private void validate(CollectionWrite body) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            errors.add(Problems.fieldError("title", "Bắt buộc"));
        }
        if (body.getVisibleFrom() != null && body.getVisibleUntil() != null
                && !body.getVisibleFrom().isBefore(body.getVisibleUntil())) {
            errors.add(Problems.fieldError("visibleUntil", "Phải sau visibleFrom"));
        }
        List<UUID> placeIds = body.getPlaceIds() == null ? List.of() : body.getPlaceIds();
        if (new HashSet<>(placeIds).size() != placeIds.size()) {
            errors.add(Problems.fieldError("placeIds", "Có địa điểm bị lặp"));
        } else if (!placeIds.isEmpty()) {
            long found = jdbc.sql("SELECT count(*) FROM place WHERE id IN (:ids)")
                    .param("ids", placeIds).query(Long.class).single();
            if (found != placeIds.size()) {
                errors.add(Problems.fieldError("placeIds", "Có địa điểm không tồn tại"));
            }
        }
        if (body.getCoverFileId() != null) {
            boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM stored_file WHERE id = :id AND purpose = 'COLLECTION')")
                    .param("id", body.getCoverFileId()).query(Boolean.class).single();
            if (!ok) {
                errors.add(Problems.fieldError("coverFileId", "Không tìm thấy ảnh bìa đã tải lên cho bộ sưu tập"));
            }
        }
        if (!errors.isEmpty()) {
            throw Problems.badRequest("Dữ liệu bộ sưu tập không hợp lệ", errors);
        }
    }

    private static Map<String, Object> params(CollectionWrite body) {
        Map<String, Object> p = new HashMap<>();
        p.put("title", body.getTitle().trim());
        p.put("body", body.getBody() == null || body.getBody().isBlank() ? null : body.getBody().trim());
        p.put("cover", body.getCoverFileId());
        p.put("from", body.getVisibleFrom());
        p.put("until", body.getVisibleUntil());
        return p;
    }

    /** Thay toàn bộ danh sách theo thứ tự gửi lên. */
    private void writeItems(UUID collectionId, List<UUID> placeIds) {
        jdbc.sql("DELETE FROM collection_item WHERE collection_id = :id").param("id", collectionId).update();
        int order = 0;
        for (UUID placeId : placeIds == null ? List.<UUID>of() : placeIds) {
            jdbc.sql("INSERT INTO collection_item (collection_id, place_id, sort_order) VALUES (:c, :p, :o)")
                    .param("c", collectionId).param("p", placeId).param("o", order++).update();
        }
    }

    private Map<String, Object> summary(UUID collectionId) {
        Map<String, Object> m = jdbc.sql("SELECT title, visible_from, visible_until FROM collection WHERE id = :id")
                .param("id", collectionId)
                .query((rs, i) -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("title", rs.getString("title"));
                    s.put("visibleFrom", String.valueOf(rs.getObject("visible_from", OffsetDateTime.class)));
                    s.put("visibleUntil", String.valueOf(rs.getObject("visible_until", OffsetDateTime.class)));
                    return s;
                })
                .optional()
                .orElseThrow(Problems::notFound);
        m.put("placeIds", jdbc.sql("SELECT place_id FROM collection_item WHERE collection_id = :id ORDER BY sort_order")
                .param("id", collectionId).query(String.class).list());
        return m;
    }

    private List<CollectionDetail> load(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, CollectionDetail> byId = new LinkedHashMap<>();
        for (UUID id : ids) {
            byId.put(id, null);
        }
        jdbc.sql("""
                SELECT c.id, c.title, c.body, c.visible_from, c.visible_until, f.storage_key AS cover_key
                FROM collection c LEFT JOIN stored_file f ON f.id = c.cover_file_id
                WHERE c.id IN (:ids)
                """)
                .param("ids", ids)
                .query(rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    byId.put(id, new CollectionDetail(id, rs.getString("title"))
                            .coverUrl(fileUrls.publicUrl(rs.getString("cover_key")))
                            .body(rs.getString("body"))
                            .visibleFrom(rs.getObject("visible_from", OffsetDateTime.class))
                            .visibleUntil(rs.getObject("visible_until", OffsetDateTime.class))
                            .places(new ArrayList<>()));
                });
        jdbc.sql("SELECT ci.collection_id, " + PlaceReader.CARD_COLUMNS + PlaceReader.CARD_FROM + """
                 JOIN collection_item ci ON ci.place_id = p.id
                 WHERE ci.collection_id IN (:ids)
                 ORDER BY ci.collection_id, ci.sort_order
                """)
                .param("ids", ids)
                .query(rs -> {
                    byId.get(rs.getObject("collection_id", UUID.class)).addPlacesItem(placeReader.mapCard(rs));
                });
        // Gắn nhãn theo từng bộ sưu tập: một địa điểm có thể nằm trong nhiều bộ, mỗi bộ giữ thẻ riêng.
        List<CollectionDetail> out = byId.values().stream().filter(c -> c != null).toList();
        out.forEach(c -> placeReader.attachTags(c.getPlaces()));
        return out;
    }
}
