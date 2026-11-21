package vn.gowithbae;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Discovery: nhãn, khu vực, bộ sưu tập (FR-01, FR-03, FR-26, RULE-01). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "app.files.public-base-url=https://cdn.test/files",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@AutoConfigureMockMvc
class DiscoveryApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    UUID visibleId;
    UUID futureId;
    UUID expiredId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE collection, place_image, place_tag, place_price, place, area, stored_file CASCADE").update();
        jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1'), ('Q3', 'Quận 3')").update();
        jdbc.sql("INSERT INTO area (code, name, status) VALUES ('OLD', 'Khu cũ', 'DEPRECATED')").update();
        jdbc.sql("""
                INSERT INTO taxonomy (group_code, code, name, status) VALUES ('VIBE', 'TEST_OLD_VIBE', 'Ngừng dùng', 'DEPRECATED')
                ON CONFLICT DO NOTHING
                """).update();

        String insertPlace = """
                INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, status)
                SELECT :code, :name, :code, (SELECT id FROM taxonomy WHERE code = 'CAFE'),
                       (SELECT id FROM area WHERE code = 'Q1'), 'addr', 10.77, 106.70, :status
                """;
        jdbc.sql(insertPlace).param("code", "P-PUB-1").param("name", "Nơi công khai 1").param("status", "PUBLISHED").update();
        jdbc.sql(insertPlace).param("code", "P-PUB-2").param("name", "Nơi công khai 2").param("status", "PUBLISHED").update();
        jdbc.sql(insertPlace).param("code", "P-DRAFT").param("name", "Nơi nháp").param("status", "DRAFT").update();
        jdbc.sql(insertPlace).param("code", "P-HIDDEN").param("name", "Nơi bị ẩn").param("status", "HIDDEN").update();

        UUID coverFile = jdbc.sql("""
                INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
                VALUES ('COLLECTION', 'image/jpeg', 100, 'collections/cover.jpg') RETURNING id
                """).query(UUID.class).single();

        visibleId = insertCollection("Đang hiển thị", "now() - interval '1 day'", "now() + interval '1 day'", coverFile);
        futureId = insertCollection("Sắp hiển thị", "now() + interval '1 day'", "NULL", null);
        expiredId = insertCollection("Đã hết hạn", "NULL", "now() - interval '1 hour'", null);
        insertCollection("Không giới hạn", "NULL", "NULL", null);

        // Thứ tự hiển thị: P-PUB-2 trước P-PUB-1; nơi nháp/ẩn nằm trong bộ sưu tập nhưng không được trả ra.
        addItem(visibleId, "P-PUB-2", 0);
        addItem(visibleId, "P-DRAFT", 1);
        addItem(visibleId, "P-HIDDEN", 2);
        addItem(visibleId, "P-PUB-1", 3);
    }

    private UUID insertCollection(String title, String from, String until, UUID coverFile) {
        return jdbc.sql("INSERT INTO collection (title, body, cover_file_id, visible_from, visible_until) "
                        + "VALUES (:title, 'Mô tả', :cover, " + from + ", " + until + ") RETURNING id")
                .param("title", title)
                .param("cover", coverFile)
                .query(UUID.class).single();
    }

    private void addItem(UUID collectionId, String placeCode, int order) {
        jdbc.sql("""
                INSERT INTO collection_item (collection_id, place_id, sort_order)
                SELECT :c, id, :o FROM place WHERE code = :code
                """).param("c", collectionId).param("code", placeCode).param("o", order).update();
    }

    @Test
    void taxonomiesReturnOnlyActiveAndFilterByGroup() throws Exception {
        mvc.perform(get("/api/v1/taxonomies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("CAFE")))
                .andExpect(jsonPath("$[*].code", not(hasItem("TEST_OLD_VIBE"))));
        mvc.perform(get("/api/v1/taxonomies").param("group", "VIBE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].group", not(hasItem("ACTIVITY"))));
    }

    @Test
    void invalidTaxonomyGroupIs400() throws Exception {
        mvc.perform(get("/api/v1/taxonomies").param("group", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void areasReturnOnlyActive() throws Exception {
        mvc.perform(get("/api/v1/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].code", not(hasItem("OLD"))));
    }

    @Test
    void collectionsListOnlyThoseInsideVisibleWindow() throws Exception {
        mvc.perform(get("/api/v1/collections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].title", hasItem("Đang hiển thị")))
                .andExpect(jsonPath("$[*].title", hasItem("Không giới hạn")))
                .andExpect(jsonPath("$[?(@.title == 'Đang hiển thị')].coverUrl")
                        .value(hasItem(matchesPattern("https://cdn\\.test/files/[0-9a-f-]{36}"))));
    }

    @Test
    void collectionDetailContainsOnlyPublishedPlacesInOrder() throws Exception {
        mvc.perform(get("/api/v1/collections/{id}", visibleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Đang hiển thị"))
                .andExpect(jsonPath("$.body").value("Mô tả"))
                .andExpect(jsonPath("$.places.length()").value(2))
                .andExpect(jsonPath("$.places[0].name").value("Nơi công khai 2"))
                .andExpect(jsonPath("$.places[1].name").value("Nơi công khai 1"))
                .andExpect(jsonPath("$.places[0].price.status").value("UNKNOWN"));
    }

    @Test
    void collectionOutsideWindowOrUnknownIs404() throws Exception {
        mvc.perform(get("/api/v1/collections/{id}", futureId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/collections/{id}", expiredId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/collections/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
