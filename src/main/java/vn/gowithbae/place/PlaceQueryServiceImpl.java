package vn.gowithbae.place;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.platform.PlaceQueryService;

/**
 * Phần B cung cấp cho module khác (danh sách đã lưu, cùng chọn, kế hoạch) cách kiểm tra và hiển thị địa điểm
 * mà không truy vấn thẳng bảng của B (RULE-01, OQ-11).
 */
@Service
public class PlaceQueryServiceImpl implements PlaceQueryService {

    private final JdbcClient jdbc;
    private final PlaceReader reader;

    public PlaceQueryServiceImpl(JdbcClient jdbc, PlaceReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    /** Chỉ địa điểm đang công khai được thêm/chốt mới; không tồn tại hoặc không công khai đều 404. */
    @Override
    public void requirePublished(UUID placeId) {
        boolean published = placeId != null && jdbc.sql("SELECT EXISTS (SELECT 1 FROM place WHERE id = :id AND status = 'PUBLISHED')")
                .param("id", placeId).query(Boolean.class).single();
        if (!published) {
            throw Problems.notFound();
        }
    }

    /**
     * Địa điểm đang công khai: thẻ đầy đủ. Đã ẩn/ngừng: chỉ giữ id và tên để danh sách cũ hiện tên kèm cảnh báo,
     * không lộ giá/ảnh/nhãn (PROP OQ-11). Không tồn tại: 404.
     */
    @Override
    public SavedPlace getSavedPlace(UUID placeId) {
        if (placeId == null) {
            throw Problems.notFound();
        }
        List<PlaceCard> published = new ArrayList<>(jdbc.sql("SELECT " + PlaceReader.CARD_COLUMNS + PlaceReader.CARD_FROM
                        + " WHERE p.id = :id AND p.status = 'PUBLISHED'")
                .param("id", placeId)
                .query((rs, i) -> reader.mapCard(rs))
                .list());
        if (!published.isEmpty()) {
            reader.attachTags(published);
            return new SavedPlace(published.getFirst(), true);
        }
        String name = jdbc.sql("SELECT name FROM place WHERE id = :id")
                .param("id", placeId).query(String.class).optional().orElseThrow(Problems::notFound);
        return new SavedPlace(new PlaceCard(placeId, name, new Price(KnownStatus.UNKNOWN), new ArrayList<>()), false);
    }
}
