package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

/** Quản trị địa điểm: biên tập, vòng đời, ảnh, quyền, audit (FR-23, FR-24, RULE-20/21/28). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=" + TestJwt.SECRET,
        "app.files.public-base-url=https://cdn.test/files",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@AutoConfigureMockMvc
class AdminPlacesApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    UUID editor;
    UUID editor2;
    UUID reviewer;
    UUID support;
    UUID superAdmin;
    String categoryId;
    String deprecatedCategoryId;
    String areaId;
    String activityTagId;
    String vibeTagId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE audit_log, collection, place_image, place_tag, place_price, opening_hours, date_exception, place, area, stored_file, app_user CASCADE").update();
        editor = user("editor@test.vn");
        editor2 = user("editor2@test.vn");
        reviewer = user("reviewer@test.vn");
        support = user("support@test.vn");
        superAdmin = user("admin@test.vn");
        jdbc.sql("""
                INSERT INTO taxonomy (group_code, code, name) VALUES ('CATEGORY', 'TEST_CAT_CAFE', 'Quán cà phê')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO taxonomy (group_code, code, name, status) VALUES ('CATEGORY', 'TEST_CAT_OLD', 'Loại cũ', 'DEPRECATED')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1')").update();
        categoryId = id("SELECT id FROM taxonomy WHERE code = 'TEST_CAT_CAFE'");
        deprecatedCategoryId = id("SELECT id FROM taxonomy WHERE code = 'TEST_CAT_OLD'");
        areaId = id("SELECT id FROM area WHERE code = 'Q1'");
        activityTagId = id("SELECT id FROM taxonomy WHERE code = 'CAFE'");
        vibeTagId = id("SELECT id FROM taxonomy WHERE code = 'QUIET'");
    }

    private UUID user(String email) {
        return jdbc.sql("INSERT INTO app_user (email, display_name) VALUES (:e, :e) RETURNING id")
                .param("e", email).query(UUID.class).single();
    }

    private String id(String sql) {
        return jdbc.sql(sql).query(UUID.class).single().toString();
    }

    private UUID placeImageFile(String purpose) {
        return jdbc.sql("""
                INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
                VALUES (:purpose, 'image/jpeg', 10, 'places/' || gen_random_uuid() || '.jpg') RETURNING id
                """).param("purpose", purpose).query(UUID.class).single();
    }

    /** Địa điểm đủ điều kiện xuất bản trừ ảnh (ảnh gắn bằng API riêng). */
    private String fullPlace(String code, String name, long standardForTwo) {
        return """
                {
                  "code": "%s", "name": "%s", "categoryId": "%s", "areaId": "%s",
                  "address": "12 Đường Thử", "lat": 10.7769, "lng": 106.7009,
                  "description": "Quán yên tĩnh", "phone": "0280000000",
                  "tagIds": ["%s", "%s"],
                  "price": { "status": "KNOWN", "standardForTwo": %d, "min": 100000, "max": 300000, "source": "Menu" },
                  "hours": {
                    "status": "KNOWN",
                    "weekly": [ { "dayOfWeek": 1, "ranges": [ { "opensAt": "07:00", "closesAt": "22:00" } ] },
                                { "dayOfWeek": 5, "ranges": [ { "opensAt": "18:00", "closesAt": "02:00" } ] } ],
                    "exceptions": [ { "date": "2030-01-01", "closed": true, "note": "Nghỉ Tết" },
                                    { "date": "2030-01-02", "closed": false, "ranges": [ { "opensAt": "10:00", "closesAt": "14:00" } ] } ]
                  },
                  "source": "Khảo sát thực tế", "verifiedAt": "2026-11-01"
                }
                """.formatted(code, name, categoryId, areaId, activityTagId, vibeTagId, standardForTwo);
    }

    private ResultActions create(UUID actor, String role, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/places")
                .header("Authorization", TestJwt.bearer(actor, role))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createId(UUID actor, String role, String body) throws Exception {
        String json = create(actor, role, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private ResultActions transition(UUID actor, String role, String placeId, String action, String reason) throws Exception {
        String body = reason == null ? "{\"action\":\"%s\"}".formatted(action)
                : "{\"action\":\"%s\",\"reason\":\"%s\"}".formatted(action, reason);
        return mvc.perform(post("/api/v1/admin/places/{id}/transitions", placeId)
                .header("Authorization", TestJwt.bearer(actor, role))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions addImage(UUID actor, String placeId, UUID fileId, boolean cover) throws Exception {
        return mvc.perform(post("/api/v1/admin/places/{id}/images", placeId)
                .header("Authorization", TestJwt.bearer(actor, "EDITOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileId\":\"%s\",\"sourceRights\":\"Ảnh tự chụp\",\"cover\":%s}".formatted(fileId, cover)));
    }

    private String readyPlace(String code, String name) throws Exception {
        String id = createId(editor, "EDITOR", fullPlace(code, name, 200000));
        addImage(editor, id, placeImageFile("PLACE_IMAGE"), true).andExpect(status().isCreated());
        return id;
    }

    private long audits(String action, String placeId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND object_id = :id")
                .param("a", action).param("id", placeId).query(Long.class).single();
    }

    // ───────────── Biên tập ─────────────

    @Test
    void createStoresDraftWithChildrenSearchTextAndAudit() throws Exception {
        String json = create(editor, "EDITOR", fullPlace("CF-001", "Cà Phê Thử", 200000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy").value(editor.toString()))
                .andExpect(jsonPath("$.category.code").value("TEST_CAT_CAFE"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.price.standardForTwo").value(200000))
                .andExpect(jsonPath("$.hours.weekly.length()").value(2))
                .andExpect(jsonPath("$.hours.weekly[1].ranges[0].closesAt").value("02:00"))
                .andExpect(jsonPath("$.hours.exceptions.length()").value(2))
                .andExpect(jsonPath("$.publishBlockers", hasItem("Chưa có ảnh có quyền sử dụng")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");
        String searchText = jdbc.sql("SELECT search_text FROM place WHERE id = CAST(:id AS uuid)")
                .param("id", id).query(String.class).single();
        assertThat(searchText).contains("ca phe thu", "quan 1", "yen tinh");
        assertThat(audits("PLACE_CREATE", id)).isEqualTo(1);
    }

    @Test
    void invalidReferencesAndPriceAre400() throws Exception {
        String body = fullPlace("CF-002", "Sai", 50000) // standardForTwo < min
                .replace(categoryId, deprecatedCategoryId);
        create(editor, "EDITOR", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("categoryId")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("price")));
        create(editor, "EDITOR", fullPlace("CF-003", "Sai", 200000).replace(categoryId, activityTagId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("categoryId")));
    }

    @Test
    void duplicateCodeIs409() throws Exception {
        createId(editor, "EDITOR", fullPlace("CF-DUP", "Một", 200000));
        create(editor, "EDITOR", fullPlace("CF-DUP", "Hai", 200000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLACE_CODE_TAKEN"));
    }

    @Test
    void updateReplacesDataAndAuditsPriceChange() throws Exception {
        String id = createId(editor, "EDITOR", fullPlace("CF-010", "Trước", 200000));
        mvc.perform(put("/api/v1/admin/places/{id}", id)
                        .header("Authorization", TestJwt.bearer(editor, "EDITOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPlace("CF-010", "Sau", 250000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sau"))
                .andExpect(jsonPath("$.price.standardForTwo").value(250000));
        Long beforePrice = jdbc.sql("""
                SELECT (before_value ->> 'standardForTwo')::bigint FROM audit_log
                WHERE action = 'PLACE_UPDATE' AND object_id = :id
                """).param("id", id).query(Long.class).single();
        assertThat(beforePrice).isEqualTo(200000L);
    }

    // ───────────── Quyền ─────────────

    @Test
    void permissionsFollowRoleMatrix() throws Exception {
        mvc.perform(get("/api/v1/admin/places")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/places").header("Authorization", TestJwt.bearer(support, "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/places").header("Authorization", TestJwt.bearer(editor, "MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/places").header("Authorization", TestJwt.bearer(reviewer, "REVIEWER")))
                .andExpect(status().isOk());
        create(reviewer, "REVIEWER", fullPlace("CF-020", "Không được tạo", 200000)).andExpect(status().isForbidden());

        String id = readyPlace("CF-021", "Chờ duyệt");
        transition(editor, "EDITOR", id, "SUBMIT", null).andExpect(status().isOk());
        transition(editor2, "EDITOR", id, "APPROVE", null).andExpect(status().isForbidden());
    }

    // ───────────── Vòng đời ─────────────

    @Test
    void fullFlowPublishesPlaceAndWritesAudit() throws Exception {
        String id = readyPlace("CF-030", "Quán Đủ Điều Kiện");
        mvc.perform(get("/api/v1/admin/places/{id}", id).header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(jsonPath("$.publishBlockers.length()").value(0))
                .andExpect(jsonPath("$.images[0].cover").value(true));
        mvc.perform(get("/api/v1/places/{id}", id)).andExpect(status().isNotFound());

        transition(editor, "EDITOR", id, "SUBMIT", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        transition(reviewer, "REVIEWER", id, "APPROVE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.reviewedBy").value(reviewer.toString()));

        mvc.perform(get("/api/v1/places/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Quán Đủ Điều Kiện"));
        mvc.perform(get("/api/v1/places").param("q", "du dieu kien"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        assertThat(audits("PLACE_SUBMIT", id)).isEqualTo(1);
        assertThat(audits("PLACE_APPROVE", id)).isEqualTo(1);

        transition(reviewer, "REVIEWER", id, "HIDE", "Đóng cửa sửa chữa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"))
                .andExpect(jsonPath("$.statusReason").value("Đóng cửa sửa chữa"));
        mvc.perform(get("/api/v1/places/{id}", id)).andExpect(status().isNotFound());
        transition(reviewer, "REVIEWER", id, "RESTORE", null)
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitWithMissingRequirementsListsBlockers() throws Exception {
        String id = createId(editor, "EDITOR", fullPlace("CF-040", "Thiếu ảnh", 200000));
        transition(editor, "EDITOR", id, "SUBMIT", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("publishBlockers")))
                .andExpect(jsonPath("$.errors[*].message", hasItem("Chưa có ảnh có quyền sử dụng")));
    }

    @Test
    void cannotApproveOwnPlaceEvenAsSuperAdmin() throws Exception {
        String id = createId(superAdmin, "SUPER_ADMIN", fullPlace("CF-050", "Tự duyệt", 200000));
        mvc.perform(post("/api/v1/admin/places/{id}/images", id)
                        .header("Authorization", TestJwt.bearer(superAdmin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"%s\",\"sourceRights\":\"Ảnh tự chụp\",\"cover\":true}".formatted(placeImageFile("PLACE_IMAGE"))))
                .andExpect(status().isCreated());
        transition(superAdmin, "SUPER_ADMIN", id, "SUBMIT", null).andExpect(status().isOk());
        transition(superAdmin, "SUPER_ADMIN", id, "APPROVE", null).andExpect(status().isForbidden());
    }

    @Test
    void invalidTransitionMissingReasonAndEditingPublishedAreRejected() throws Exception {
        String id = readyPlace("CF-060", "Vòng đời");
        transition(reviewer, "REVIEWER", id, "APPROVE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        transition(editor, "EDITOR", id, "SUBMIT", null).andExpect(status().isOk());
        transition(reviewer, "REVIEWER", id, "RETURN", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        transition(reviewer, "REVIEWER", id, "APPROVE", null).andExpect(status().isOk());

        mvc.perform(put("/api/v1/admin/places/{id}", id)
                        .header("Authorization", TestJwt.bearer(editor, "EDITOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPlace("CF-060", "Sửa bản công khai", 200000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_EDITABLE"));
        addImage(editor, id, placeImageFile("PLACE_IMAGE"), false).andExpect(status().isConflict());
    }

    @Test
    void duplicateNameNearbyIsABlocker() throws Exception {
        String first = readyPlace("CF-070", "Quán Trùng Tên");
        transition(editor, "EDITOR", first, "SUBMIT", null).andExpect(status().isOk());
        String second = readyPlace("CF-071", "quán trùng tên");
        mvc.perform(get("/api/v1/admin/places/{id}", second).header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(jsonPath("$.publishBlockers", hasItem("Có thể trùng với địa điểm CF-070")));
    }

    // ───────────── Ảnh ─────────────

    @Test
    void imagesRequirePlaceImageFileAndBelongToPlace() throws Exception {
        String id = createId(editor, "EDITOR", fullPlace("CF-080", "Ảnh", 200000));
        addImage(editor, id, placeImageFile("AVATAR"), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fileId"));

        String first = JsonPath.read(addImage(editor, id, placeImageFile("PLACE_IMAGE"), true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").exists())
                .andReturn().getResponse().getContentAsString(), "$.id");
        addImage(editor, id, placeImageFile("PLACE_IMAGE"), true).andExpect(status().isCreated());
        long covers = jdbc.sql("SELECT count(*) FROM place_image WHERE place_id = CAST(:id AS uuid) AND is_cover")
                .param("id", id).query(Long.class).single();
        assertThat(covers).isEqualTo(1);

        String other = createId(editor, "EDITOR", fullPlace("CF-081", "Nơi khác", 200000));
        mvc.perform(delete("/api/v1/admin/places/{p}/images/{i}", other, first)
                        .header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/admin/places/{p}/images/{i}", id, first)
                        .header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(status().isNoContent());
    }

    @Test
    void listFiltersByStatusAndQuery() throws Exception {
        createId(editor, "EDITOR", fullPlace("CF-090", "Nháp Một", 200000));
        String pending = readyPlace("CF-091", "Chờ Hai");
        transition(editor, "EDITOR", pending, "SUBMIT", null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/places").param("status", "PENDING")
                        .header("Authorization", TestJwt.bearer(reviewer, "REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].code").value("CF-091"));
        mvc.perform(get("/api/v1/admin/places").param("q", "cf-090")
                        .header("Authorization", TestJwt.bearer(reviewer, "REVIEWER")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }
}
