package vn.gowithbae.place;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.platform.PlaceQueryService;

/**
 * Triển khai {@link PlaceQueryService} (giao diện phần A) dựa trên {@link PlaceReader} (phần B),
 * để {@code SavedListsDelegate} và các module dùng chung một chuẩn thẻ/trạng thái địa điểm.
 */
@Service
public class PlaceQueryServiceImpl implements PlaceQueryService {

    private final JdbcClient jdbc;
    private final PlaceReader reader;

    public PlaceQueryServiceImpl(JdbcClient jdbc, PlaceReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    @Override
    public void requirePublished(UUID placeId) {
        if (placeId == null) {
            throw Problems.notFound();
        }
        boolean published = Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM place WHERE id = :id AND status = 'PUBLISHED')
                """)
                .param("id", placeId)
                .query(Boolean.class)
                .single());
        if (!published) {
            throw Problems.notFound();
        }
    }

    @Override
    public SavedPlace getSavedPlace(UUID placeId) {
        if (placeId == null) {
            throw Problems.notFound();
        }
        record Row(PlaceCard card, String status) {}
        Row row = jdbc.sql("SELECT " + PlaceReader.CARD_COLUMNS + ", p.status" + PlaceReader.CARD_FROM + " WHERE p.id = :id")
                .param("id", placeId)
                .query((rs, i) -> new Row(reader.mapCard(rs), rs.getString("status")))
                .optional()
                .orElseThrow(Problems::notFound);
        reader.attachTags(List.of(row.card()));
        return new SavedPlace(row.card(), "PUBLISHED".equals(row.status()));
    }
}
