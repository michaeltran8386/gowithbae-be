package vn.gowithbae.place;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.DiscoveryApiDelegate;
import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.Area;
import vn.gowithbae.api.model.CollectionDetail;
import vn.gowithbae.api.model.CollectionSummary;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;

@Service
public class DiscoveryDelegate implements DiscoveryApiDelegate {

    private final JdbcClient jdbc;
    private final PlacesDelegate placesDelegate;

    public static final Map<String, String> COLLECTION_COVERS = Map.of(
            "41b27255-1d84-4e07-9cf0-5f663d39d79b", "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=800&auto=format&fit=crop&q=80",
            "950d350a-84a0-4495-a075-048f509c5ce8", "https://images.unsplash.com/photo-1550966871-3ed3cdb5ed0c?w=800&auto=format&fit=crop&q=80"
    );

    public static final String DEFAULT_COLLECTION_COVER = "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=800&auto=format&fit=crop&q=80";

    public DiscoveryDelegate(JdbcClient jdbc, PlacesDelegate placesDelegate) {
        this.jdbc = jdbc;
        this.placesDelegate = placesDelegate;
    }

    @Override
    public ResponseEntity<List<Taxonomy>> listTaxonomies(TaxonomyGroup group) {
        String sql = "SELECT id, group_code, code, name, status FROM taxonomy WHERE status = 'ACTIVE'";
        var query = group != null
                ? jdbc.sql(sql + " AND group_code = :group ORDER BY name").param("group", group.getValue())
                : jdbc.sql(sql + " ORDER BY name");
        List<Taxonomy> items = query.query((rs, i) -> new Taxonomy(
                rs.getObject("id", UUID.class),
                TaxonomyGroup.fromValue(rs.getString("group_code")),
                rs.getString("code"),
                rs.getString("name"),
                ActiveStatus.fromValue(rs.getString("status"))
        )).list();
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<List<Area>> listAreas() {
        List<Area> items = jdbc.sql("SELECT id, code, name, status FROM area WHERE status = 'ACTIVE' ORDER BY name")
                .query((rs, i) -> new Area(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        ActiveStatus.fromValue(rs.getString("status"))
                )).list();
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<List<CollectionSummary>> listCollections() {
        List<CollectionSummary> items = jdbc.sql("""
                SELECT id, title FROM collection
                WHERE (visible_from IS NULL OR visible_from <= now())
                  AND (visible_until IS NULL OR visible_until >= now())
                ORDER BY updated_at DESC
                """)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String cover = COLLECTION_COVERS.getOrDefault(id.toString(), DEFAULT_COLLECTION_COVER);
                    return new CollectionSummary(
                            id,
                            rs.getString("title")
                    ).coverUrl(URI.create(cover));
                }).list();
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<CollectionDetail> getCollection(UUID collectionId) {
        var detailOpt = jdbc.sql("""
                SELECT id, title, body, visible_from, visible_until
                FROM collection
                WHERE id = :id
                  AND (visible_from IS NULL OR visible_from <= now())
                  AND (visible_until IS NULL OR visible_until >= now())
                """)
                .param("id", collectionId)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String cover = COLLECTION_COVERS.getOrDefault(id.toString(), DEFAULT_COLLECTION_COVER);
                    return new CollectionDetail(
                            id,
                            rs.getString("title")
                    ).body(rs.getString("body")).coverUrl(URI.create(cover));
                })
                .optional();

        if (detailOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bộ sưu tập");
        }

        var detail = detailOpt.get();

        List<UUID> placeIds = jdbc.sql("""
                SELECT place_id FROM collection_item
                WHERE collection_id = :id
                ORDER BY sort_order
                """)
                .param("id", collectionId)
                .query((rs, i) -> rs.getObject("place_id", UUID.class))
                .list();

        List<PlaceCard> places = new ArrayList<>();
        for (UUID pid : placeIds) {
            var card = placesDelegate.fetchPlaceCard(pid);
            if (card != null) {
                places.add(card);
            }
        }
        detail.setPlaces(places);

        return ResponseEntity.ok(detail);
    }
}
