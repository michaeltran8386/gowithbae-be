package vn.gowithbae.place;

import java.net.URI;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.PlacesApiDelegate;
import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.Area;
import vn.gowithbae.api.model.BudgetBand;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.OpeningHours;
import vn.gowithbae.api.model.OpeningHoursWeeklyInner;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.PlaceDetail;
import vn.gowithbae.api.model.PlaceImage;
import vn.gowithbae.api.model.PlaceSearchPage;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.api.model.TimeRange;
import vn.gowithbae.platform.PlaceQueryService;

/**
 * PlacesDelegate — triển khai tìm kiếm, chi tiết địa điểm, so sánh và cung cấp PlaceQueryService cho SavedLists.
 */
@Service
public class PlacesDelegate implements PlacesApiDelegate, PlaceQueryService {

    private final JdbcClient jdbc;

    public static final Map<String, String> DEFAULT_COVERS = Map.of(
            "59a5a69a-6070-41bf-bfc7-7dd10795086b", "https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=800&auto=format&fit=crop&q=80",
            "65b065d3-34e8-4633-a384-650580b0a2cd", "https://images.unsplash.com/photo-1565193566173-7a0ee3dbe261?w=800&auto=format&fit=crop&q=80",
            "c8ce3ab9-8a81-4056-8bc4-48a2b4c7722b", "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=800&auto=format&fit=crop&q=80",
            "b4b8a6a0-2a5c-4ba5-bcf8-4ff5c0557ae6", "https://images.unsplash.com/photo-1514933651103-005eec06c04b?w=800&auto=format&fit=crop&q=80",
            "78a52478-ba00-4a67-97c0-eb12a8596de7", "https://images.unsplash.com/photo-1519331379826-f10be5486c6f?w=800&auto=format&fit=crop&q=80",
            "99e66425-6094-48cd-9a26-42c2c8884b81", "https://images.unsplash.com/photo-1460661419200-1868aeb56fd4?w=800&auto=format&fit=crop&q=80",
            "a1c13584-101d-45c0-8f68-7f5aa00b39ba", "https://images.unsplash.com/photo-1550966871-3ed3cdb5ed0c?w=800&auto=format&fit=crop&q=80"
    );

    public static final String FALLBACK_COVER = "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=800&auto=format&fit=crop&q=80";

    public static String getCoverForPlace(UUID id) {
        if (id == null) return FALLBACK_COVER;
        return DEFAULT_COVERS.getOrDefault(id.toString(), FALLBACK_COVER);
    }

    public PlacesDelegate(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResponseEntity<PlaceSearchPage> searchPlaces(String q, UUID areaId, Double lat, Double lng, Double radiusKm,
            BudgetBand budget, OffsetDateTime at, List<UUID> tagIds, String sort, Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;

        var where = new StringBuilder("p.status = 'PUBLISHED'");
        var params = new java.util.HashMap<String, Object>();
        String needle = normalize(q);
        if (!needle.isEmpty()) {
            where.append(" AND p.search_text LIKE :q ESCAPE '\\'");
            params.put("q", "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (areaId != null) {
            where.append(" AND p.area_id = :areaId");
            params.put("areaId", areaId);
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.taxonomy_id IN (:tagIds))");
            params.put("tagIds", tagIds);
        }
        if (budget != null) {
            where.append(" AND pp.status = 'KNOWN' AND ").append(switch (budget) {
                case FREE -> "pp.standard_for_two = 0";
                case UNDER_200_K -> "pp.standard_for_two > 0 AND pp.standard_for_two < 200000";
                case FROM_200_K_TO_400_K -> "pp.standard_for_two >= 200000 AND pp.standard_for_two < 400000";
                case FROM_400_K_TO_700_K -> "pp.standard_for_two >= 400000 AND pp.standard_for_two <= 700000";
                case OVER_700_K -> "pp.standard_for_two > 700000";
            });
        }
        String orderBy;
        if ("NEAREST".equals(sort) && lat != null && lng != null) {
            orderBy = "((p.lat - :userLat)*(p.lat - :userLat) + (p.lng - :userLng)*(p.lng - :userLng)) ASC NULLS LAST, p.code";
            params.put("userLat", lat);
            params.put("userLng", lng);
        } else if ("PRICE_ASC".equals(sort)) {
            orderBy = "pp.standard_for_two ASC NULLS LAST, p.code";
        } else {
            orderBy = "p.verified_at DESC NULLS LAST, p.code";
        }

        String from = " FROM place p LEFT JOIN place_price pp ON pp.place_id = p.id"
                + " LEFT JOIN area a ON a.id = p.area_id WHERE " + where;

        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();

        List<PlaceCard> items = jdbc.sql("""
                SELECT p.id, p.name, p.lat, p.lng, a.name AS area_name,
                       coalesce(pp.status, 'UNKNOWN') AS price_status, pp.standard_for_two, pp.min_price, pp.max_price,
                       pp.includes, pp.verified_at AS price_verified_at
                """ + from + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", s)
                .param("offset", (long) p * s)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String name = rs.getString("name");
                    String cover = getCoverForPlace(id);
                    var price = new Price(KnownStatus.fromValue(rs.getString("price_status")))
                            .standardForTwo(rs.getObject("standard_for_two", Long.class))
                            .min(rs.getObject("min_price", Long.class))
                            .max(rs.getObject("max_price", Long.class))
                            .includes(rs.getString("includes"))
                            .verifiedAt(rs.getObject("price_verified_at", java.time.LocalDate.class));
                    PlaceCard card = new PlaceCard(id, name, price, new ArrayList<>())
                            .areaName(rs.getString("area_name"))
                            .lat(rs.getDouble("lat"))
                            .lng(rs.getDouble("lng"))
                            .coverUrl(cover != null ? URI.create(cover) : null);

                    if (lat != null && lng != null && rs.getObject("lat") != null && rs.getObject("lng") != null) {
                        double dLat = Math.toRadians(rs.getDouble("lat") - lat);
                        double dLng = Math.toRadians(rs.getDouble("lng") - lng);
                        double sinLat = Math.sin(dLat / 2);
                        double sinLng = Math.sin(dLng / 2);
                        double a = sinLat * sinLat + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(rs.getDouble("lat"))) * sinLng * sinLng;
                        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
                        card.setDistanceKm(6371.0 * c);
                    }
                    return card;
                })
                .list();

        attachTags(items);

        var meta = new PageMeta(p, s, total, (int) ((total + s - 1) / s));
        return ResponseEntity.ok(new PlaceSearchPage(UUID.randomUUID(), items, meta));
    }

    @Override
    public ResponseEntity<vn.gowithbae.api.model.PlaceReportReceipt> reportPlace(UUID placeId, vn.gowithbae.api.model.PlaceReportCreate placeReportCreate) {
        requirePublished(placeId);

        UUID reportId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        jdbc.sql("""
                INSERT INTO place_report (id, place_id, reason, description, file_id, status, created_at, updated_at)
                VALUES (:id, :placeId, :reason, :desc, :fileId, 'NEW', :now, :now)
                """)
                .param("id", reportId)
                .param("placeId", placeId)
                .param("reason", placeReportCreate.getReason().getValue())
                .param("desc", placeReportCreate.getDescription())
                .param("fileId", placeReportCreate.getFileId())
                .param("now", now)
                .update();

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new vn.gowithbae.api.model.PlaceReportReceipt(reportId, vn.gowithbae.api.model.PlaceReportStatus.NEW, now)
        );
    }

    @Override
    public ResponseEntity<PlaceDetail> getPlace(UUID placeId) {
        var detail = fetchPlaceDetail(placeId);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy địa điểm hoặc chưa công khai");
        }
        return ResponseEntity.ok(detail);
    }

    @Override
    public ResponseEntity<List<PlaceDetail>> comparePlaces(Set<UUID> ids, Double lat, Double lng) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<PlaceDetail> result = new ArrayList<>();
        for (UUID id : ids) {
            var detail = fetchPlaceDetail(id);
            if (detail != null) {
                result.add(detail);
            }
        }
        return ResponseEntity.ok(result);
    }

    @Override
    public void requirePublished(UUID placeId) {
        boolean ok = jdbc.sql("SELECT count(*) FROM place WHERE id = :id AND status = 'PUBLISHED'")
                .param("id", placeId).query(Long.class).single() > 0;
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không khả dụng");
        }
    }

    @Override
    public SavedPlace getSavedPlace(UUID placeId) {
        var card = fetchPlaceCard(placeId);
        if (card != null) {
            return new SavedPlace(card, true);
        }
        var historical = jdbc.sql("SELECT name FROM place WHERE id = :id")
                .param("id", placeId)
                .query((rs, i) -> rs.getString("name"))
                .optional();
        if (historical.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại");
        }
        var placeholder = new PlaceCard(placeId, historical.get(), new Price(KnownStatus.UNKNOWN), new ArrayList<>());
        return new SavedPlace(placeholder, false);
    }

    public PlaceDetail fetchPlaceDetail(UUID placeId) {
        var placeOpt = jdbc.sql("""
                SELECT p.id, p.code, p.name, p.slug, p.status, p.address, p.lat, p.lng,
                       p.description, p.phone, p.website_url, p.notes, p.reservation_recommended,
                       p.verified_at, a.id AS area_id, a.code AS area_code, a.name AS area_name,
                       a.status AS area_status,
                       coalesce(pp.status, 'UNKNOWN') AS price_status, pp.standard_for_two,
                       pp.min_price, pp.max_price, pp.includes, pp.verified_at AS price_verified_at
                FROM place p
                JOIN area a ON a.id = p.area_id
                LEFT JOIN place_price pp ON pp.place_id = p.id
                WHERE p.id = :id AND p.status = 'PUBLISHED'
                """)
                .param("id", placeId)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String name = rs.getString("name");
                    String cover = getCoverForPlace(id);

                    var price = new Price(KnownStatus.fromValue(rs.getString("price_status")))
                            .standardForTwo(rs.getObject("standard_for_two", Long.class))
                            .min(rs.getObject("min_price", Long.class))
                            .max(rs.getObject("max_price", Long.class))
                            .includes(rs.getString("includes"))
                            .verifiedAt(rs.getObject("price_verified_at", java.time.LocalDate.class));

                    var area = new Area(
                            rs.getObject("area_id", UUID.class),
                            rs.getString("area_code"),
                            rs.getString("area_name"),
                            ActiveStatus.fromValue(rs.getString("area_status")));

                    var hours = new OpeningHours(KnownStatus.UNKNOWN);
                    List<OpeningHoursWeeklyInner> weekly = jdbc.sql("""
                            SELECT day_of_week, opens_at, closes_at
                            FROM opening_hours
                            WHERE place_id = :id
                            ORDER BY day_of_week
                            """)
                            .param("id", id)
                            .query((hrs, idx) -> {
                                int dow = hrs.getInt("day_of_week");
                                String opens = hrs.getString("opens_at");
                                String closes = hrs.getString("closes_at");
                                if (opens != null && opens.length() > 5) opens = opens.substring(0, 5);
                                if (closes != null && closes.length() > 5) closes = closes.substring(0, 5);
                                TimeRange tr = new TimeRange(opens, closes);
                                return new OpeningHoursWeeklyInner(dow, List.of(tr));
                            }).list();
                    if (!weekly.isEmpty()) {
                        hours.setStatus(KnownStatus.KNOWN);
                        hours.setWeekly(weekly);
                    }
                    List<PlaceImage> images = new ArrayList<>();
                    if (cover != null) {
                        images.add(new PlaceImage(UUID.randomUUID(), URI.create(cover), 0, true));
                    }

                    return new PlaceDetail(
                            id,
                            rs.getString("code"),
                            name,
                            images,
                            price,
                            new ArrayList<>(),
                            hours
                    )
                    .area(area)
                    .address(rs.getString("address"))
                    .description(rs.getString("description"))
                    .phone(rs.getString("phone"))
                    .website(rs.getString("website_url"))
                    .lat(rs.getDouble("lat"))
                    .lng(rs.getDouble("lng"))
                    .notes(rs.getString("notes"))
                    .reservationRecommended(rs.getObject("reservation_recommended", Boolean.class));
                })
                .optional();

        if (placeOpt.isEmpty()) {
            return null;
        }

        var detail = placeOpt.get();

        jdbc.sql("""
                SELECT t.id, t.group_code, t.code, t.name, t.status
                FROM place_tag pt
                JOIN taxonomy t ON t.id = pt.taxonomy_id
                WHERE pt.place_id = :id AND t.status = 'ACTIVE'
                ORDER BY t.group_code, t.name
                """)
                .param("id", placeId)
                .query(rs -> {
                    detail.addTagsItem(new Taxonomy(
                            rs.getObject("id", UUID.class),
                            TaxonomyGroup.fromValue(rs.getString("group_code")),
                            rs.getString("code"),
                            rs.getString("name"),
                            ActiveStatus.fromValue(rs.getString("status"))
                    ));
                });

        return detail;
    }

    public PlaceCard fetchPlaceCard(UUID placeId) {
        var cardOpt = jdbc.sql("""
                SELECT p.id, p.name, p.lat, p.lng, a.name AS area_name,
                       coalesce(pp.status, 'UNKNOWN') AS price_status, pp.standard_for_two, pp.min_price, pp.max_price,
                       pp.includes, pp.verified_at AS price_verified_at
                FROM place p
                JOIN area a ON a.id = p.area_id
                LEFT JOIN place_price pp ON pp.place_id = p.id
                WHERE p.id = :id AND p.status = 'PUBLISHED'
                """)
                .param("id", placeId)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String name = rs.getString("name");
                    String cover = getCoverForPlace(id);
                    var price = new Price(KnownStatus.fromValue(rs.getString("price_status")))
                            .standardForTwo(rs.getObject("standard_for_two", Long.class))
                            .min(rs.getObject("min_price", Long.class))
                            .max(rs.getObject("max_price", Long.class))
                            .includes(rs.getString("includes"))
                            .verifiedAt(rs.getObject("price_verified_at", java.time.LocalDate.class));
                    return new PlaceCard(id, name, price, new ArrayList<>())
                            .areaName(rs.getString("area_name"))
                            .lat(rs.getDouble("lat"))
                            .lng(rs.getDouble("lng"))
                            .coverUrl(cover != null ? URI.create(cover) : null);
                })
                .optional();

        if (cardOpt.isEmpty()) {
            return null;
        }
        var card = cardOpt.get();
        attachTags(List.of(card));
        return card;
    }

    /** Tối đa 3 nhãn đang dùng trên thẻ (FR-02). */
    private void attachTags(List<PlaceCard> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<UUID, PlaceCard> byId = items.stream().collect(Collectors.toMap(PlaceCard::getId, c -> c));
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

    /** Bỏ dấu tiếng Việt + lowercase; cùng quy tắc phải dùng khi ghi place.search_text. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String noMarks = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(java.util.Locale.ROOT);
    }
}
