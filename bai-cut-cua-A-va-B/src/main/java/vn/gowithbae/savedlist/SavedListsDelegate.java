package vn.gowithbae.savedlist;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.SavedListsApiDelegate;
import vn.gowithbae.api.model.SavedListDetail;
import vn.gowithbae.api.model.SavedListDetailAllOfItems;
import vn.gowithbae.api.model.SavedListSummary;
import vn.gowithbae.api.model.SavedListWrite;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.PlaceQueryService;

@Service
@Transactional
public class SavedListsDelegate implements SavedListsApiDelegate {
    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final ObjectProvider<PlaceQueryService> places;

    public SavedListsDelegate(JdbcClient jdbc, CurrentUser user, ObjectProvider<PlaceQueryService> places) {
        this.jdbc = jdbc;
        this.user = user;
        this.places = places;
    }

    private UUID member() {
        user.requireActive();
        user.requireRole("MEMBER");
        return user.id();
    }

    private SavedListSummary owned(UUID id) {
        // Serialize item mutations and deletion on the parent, including duplicate PUTs.
        return jdbc.sql("""
                SELECT l.*, (SELECT count(*) FROM saved_list_item i WHERE i.list_id = l.id) AS place_count
                FROM saved_list l WHERE l.id = :id AND l.owner_id = :owner FOR UPDATE OF l
                """).param("id", id).param("owner", member()).query(this::summary).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved list not found"));
    }

    private SavedListSummary summary(ResultSet rs, int row) throws SQLException {
        return new SavedListSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getInt("place_count"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private String name(SavedListWrite body) {
        String name = body == null ? null : body.getName();
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must contain 1–100 characters and not be blank");
        }
        return name.strip();
    }

    private PlaceQueryService placeService() {
        // shortcut: B's provider is pending; only new saves and nonempty detail reads need it.
        PlaceQueryService service = places.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "PlaceQueryService provider from member B is not installed");
        }
        return service;
    }

    @Override
    public ResponseEntity<List<SavedListSummary>> listSavedLists() {
        return ResponseEntity.ok(jdbc.sql("""
                SELECT l.*, (SELECT count(*) FROM saved_list_item i WHERE i.list_id = l.id) AS place_count
                FROM saved_list l WHERE l.owner_id = :owner ORDER BY l.created_at DESC, l.id DESC
                """).param("owner", member()).query(this::summary).list());
    }

    @Override
    public ResponseEntity<SavedListSummary> createSavedList(SavedListWrite body) {
        UUID owner = member();
        var result = jdbc.sql("""
                INSERT INTO saved_list(owner_id, name) VALUES (:owner, :name)
                RETURNING *, 0 AS place_count
                """).param("owner", owner).param("name", name(body)).query(this::summary).single();
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Override
    public ResponseEntity<SavedListSummary> renameSavedList(UUID id, SavedListWrite body) {
        var result = owned(id);
        String name = name(body);
        jdbc.sql("UPDATE saved_list SET name = :name, updated_at = now() WHERE id = :id")
                .param("name", name).param("id", id).update();
        return ResponseEntity.ok(result.name(name));
    }

    @Override
    public ResponseEntity<Void> deleteSavedList(UUID id) {
        owned(id);
        jdbc.sql("DELETE FROM saved_list WHERE id = :id").param("id", id).update();
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SavedListDetail> getSavedList(UUID id) {
        var list = owned(id);
        record Item(UUID placeId, OffsetDateTime addedAt) {}
        var rows = jdbc.sql("SELECT place_id, added_at FROM saved_list_item WHERE list_id = :id ORDER BY added_at DESC, place_id DESC")
                .param("id", id).query((rs, n) -> new Item(rs.getObject("place_id", UUID.class),
                        rs.getObject("added_at", OffsetDateTime.class))).list();
        List<SavedListDetailAllOfItems> items = List.of();
        if (!rows.isEmpty()) {
            var service = placeService();
            items = rows.stream().map(item -> {
                var saved = service.getSavedPlace(item.placeId());
                return new SavedListDetailAllOfItems(saved.place(), saved.available(), item.addedAt());
            }).toList();
        }
        return ResponseEntity.ok(new SavedListDetail(list.getId(), list.getName(), items.size(), list.getCreatedAt(), items));
    }

    @Override
    public ResponseEntity<Void> addPlaceToList(UUID id, UUID placeId) {
        owned(id);
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM saved_list_item WHERE list_id = :id AND place_id = :place)")
                .param("id", id).param("place", placeId).query(Boolean.class).single();
        if (!exists) {
            placeService().requirePublished(placeId);
            jdbc.sql("INSERT INTO saved_list_item(list_id, place_id) VALUES (:id, :place) ON CONFLICT DO NOTHING")
                    .param("id", id).param("place", placeId).update();
        }
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> removePlaceFromList(UUID id, UUID placeId) {
        owned(id);
        jdbc.sql("DELETE FROM saved_list_item WHERE list_id = :id AND place_id = :place")
                .param("id", id).param("place", placeId).update();
        return ResponseEntity.noContent().build();
    }
}
