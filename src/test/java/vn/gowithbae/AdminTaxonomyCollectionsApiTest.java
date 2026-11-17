package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

/** Quản lý nhãn, khu vực (FR-25, RULE-22) và bộ sưu tập (FR-26). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "app.files.public-base-url=https://cdn.test/files",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@AutoConfigureMockMvc
class AdminTaxonomyCollectionsApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;

    /** Token thật của phần A: gán vai trò trong user_role và tạo access_session (TestJwtFactory). */
    private String bearer(UUID userId, String role) {
        return "Bearer " + new TestJwtFactory(jdbc, encoder).token(userId, role);
    }

    UUID admin;
    UUID editor;
    UUID reviewer;
    UUID published;
    UUID draft;
    UUID areaId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE audit_log, collection, place_image, place_tag, place_price, opening_hours, date_exception, place, area, stored_file, app_user CASCADE").update();
        jdbc.sql("DELETE FROM taxonomy WHERE code LIKE 'T\\_%'").update();
        admin = user("admin@test.vn");
        editor = user("editor@test.vn");
        reviewer = user("reviewer@test.vn");
        areaId = jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1') RETURNING id").query(UUID.class).single();
        published = place("P-PUB", "Nơi Công Khai", "PUBLISHED");
        draft = place("P-DRAFT", "Nơi Nháp", "DRAFT");
        jdbc.sql("INSERT INTO place_tag (place_id, taxonomy_id) SELECT :p, id FROM taxonomy WHERE code = 'COZY'")
                .param("p", published).update();
    }

    private UUID user(String email) {
        return jdbc.sql("INSERT INTO app_user (email, display_name) VALUES (:e, :e) RETURNING id")
                .param("e", email).query(UUID.class).single();
    }

    private UUID place(String code, String name, String status) {
        return jdbc.sql("""
                INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, status)
                SELECT :code, :name, '', (SELECT id FROM taxonomy WHERE code = 'CAFE'), :area, 'addr', 10.77, 106.70, :status
                RETURNING id
                """).param("code", code).param("name", name).param("area", areaId).param("status", status)
                .query(UUID.class).single();
    }

    private ResultActions call(MockHttpServletRequestBuilder req, UUID actor, String role, String body) throws Exception {
        req.header("Authorization", bearer(actor, role));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private String searchText(UUID placeId) {
        return jdbc.sql("SELECT search_text FROM place WHERE id = :id").param("id", placeId).query(String.class).single();
    }

    private String cozyId() {
        return jdbc.sql("SELECT id FROM taxonomy WHERE code = 'COZY'").query(UUID.class).single().toString();
    }

    // ───────────── Nhãn ─────────────

    @Test
    void superAdminCreatesTaxonomyAndDuplicatesConflict() throws Exception {
        String body = "{\"group\":\"VIBE\",\"code\":\"T_CHILL\",\"name\":\"Thư giãn\"}";
        call(post("/api/v1/admin/taxonomies"), admin, "SUPER_ADMIN", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.group").value("VIBE"));
        call(post("/api/v1/admin/taxonomies"), admin, "SUPER_ADMIN", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAXONOMY_CODE_TAKEN"));
        call(post("/api/v1/admin/taxonomies"), editor, "EDITOR", body.replace("T_CHILL", "T_OTHER"))
                .andExpect(status().isForbidden());
        call(post("/api/v1/admin/taxonomies"), admin, "SUPER_ADMIN", body.replace("T_CHILL", "bad code"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/taxonomies").param("group", "VIBE"))
                .andExpect(jsonPath("$[*].code", hasItem("T_CHILL")));
    }

    @Test
    void deprecatingTagHidesItButKeepsAssignmentAndRefreshesSearchText() throws Exception {
        String cozy = cozyId();
        try {
            call(patch("/api/v1/admin/taxonomies/{id}", cozy), admin, "SUPER_ADMIN", "{\"status\":\"DEPRECATED\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEPRECATED"))
                    .andExpect(jsonPath("$.name").value("Ấm áp"));
            mvc.perform(get("/api/v1/taxonomies")).andExpect(jsonPath("$[*].code", not(hasItem("COZY"))));
            call(get("/api/v1/admin/taxonomies").param("group", "VIBE"), editor, "EDITOR", null)
                    .andExpect(jsonPath("$[?(@.code == 'COZY')].status").value("DEPRECATED"));
            mvc.perform(get("/api/v1/places/{id}", published)).andExpect(jsonPath("$.tags.length()").value(0));
            long kept = jdbc.sql("SELECT count(*) FROM place_tag WHERE place_id = :p").param("p", published)
                    .query(Long.class).single();
            assertThat(kept).isEqualTo(1);
            assertThat(searchText(published)).doesNotContain("am ap");
        } finally {
            jdbc.sql("UPDATE taxonomy SET status = 'ACTIVE', name = 'Ấm áp' WHERE code = 'COZY'").update();
        }
    }

    @Test
    void renamingTagUpdatesSearchTextAndAudit() throws Exception {
        String cozy = cozyId();
        try {
            call(patch("/api/v1/admin/taxonomies/{id}", cozy), admin, "SUPER_ADMIN", "{\"name\":\"Ấm cúng\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Ấm cúng"));
            assertThat(searchText(published)).contains("am cung");
            long audits = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'TAXONOMY_UPDATE' AND before_value ->> 'name' = 'Ấm áp'")
                    .query(Long.class).single();
            assertThat(audits).isEqualTo(1);
        } finally {
            jdbc.sql("UPDATE taxonomy SET status = 'ACTIVE', name = 'Ấm áp' WHERE code = 'COZY'").update();
        }
        call(patch("/api/v1/admin/taxonomies/{id}", UUID.randomUUID()), admin, "SUPER_ADMIN", "{\"name\":\"x\"}")
                .andExpect(status().isNotFound());
        call(patch("/api/v1/admin/taxonomies/{id}", cozy), admin, "SUPER_ADMIN", "{\"name\":\"  \"}")
                .andExpect(status().isBadRequest());
    }

    // ───────────── Khu vực ─────────────

    @Test
    void areasCreateRenameAndDeprecate() throws Exception {
        call(post("/api/v1/admin/areas"), admin, "SUPER_ADMIN", "{\"code\":\"Q3\",\"name\":\"Quận 3\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("Q3"));
        call(post("/api/v1/admin/areas"), admin, "SUPER_ADMIN", "{\"code\":\"Q3\",\"name\":\"Trùng\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AREA_CODE_TAKEN"));

        call(patch("/api/v1/admin/areas/{id}", areaId), admin, "SUPER_ADMIN", "{\"name\":\"Quận Một\"}")
                .andExpect(status().isOk());
        assertThat(searchText(published)).contains("quan mot");

        call(patch("/api/v1/admin/areas/{id}", areaId), admin, "SUPER_ADMIN", "{\"status\":\"DEPRECATED\"}")
                .andExpect(jsonPath("$.status").value("DEPRECATED"));
        mvc.perform(get("/api/v1/areas")).andExpect(jsonPath("$[*].code", not(hasItem("Q1"))));
        mvc.perform(get("/api/v1/places/{id}", published)).andExpect(jsonPath("$.area.status").value("DEPRECATED"));
        call(patch("/api/v1/admin/areas/{id}", areaId), reviewer, "REVIEWER", "{\"name\":\"x\"}")
                .andExpect(status().isForbidden());
    }

    // ───────────── Bộ sưu tập ─────────────

    private String collectionBody(String title, String from, String until, UUID... placeIds) {
        StringBuilder ids = new StringBuilder();
        for (UUID id : placeIds) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return """
                {"title":"%s","body":"Mô tả","visibleFrom":%s,"visibleUntil":%s,"placeIds":[%s]}
                """.formatted(title, from == null ? "null" : '"' + from + '"', until == null ? "null" : '"' + until + '"', ids);
    }

    @Test
    void collectionCrudKeepsOrderAndPublicViewHidesDrafts() throws Exception {
        String json = call(post("/api/v1/admin/collections"), editor, "EDITOR",
                        collectionBody("Hẹn đầu", null, null, draft, published))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.places.length()").value(2))
                .andExpect(jsonPath("$.places[0].name").value("Nơi Nháp"))
                .andExpect(jsonPath("$.places[1].tags[0].code").value("COZY"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");

        mvc.perform(get("/api/v1/collections/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places.length()").value(1))
                .andExpect(jsonPath("$.places[0].name").value("Nơi Công Khai"));

        call(put("/api/v1/admin/collections/{id}", id), editor, "EDITOR",
                        collectionBody("Hẹn đầu (sửa)", "2020-01-01T00:00:00+07:00", null, published))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hẹn đầu (sửa)"))
                .andExpect(jsonPath("$.places.length()").value(1));

        call(get("/api/v1/admin/collections"), reviewer, "REVIEWER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        call(delete("/api/v1/admin/collections/{id}", id), editor, "EDITOR", null).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/collections/{id}", id)).andExpect(status().isNotFound());
        long audits = jdbc.sql("SELECT count(*) FROM audit_log WHERE object_type = 'collection' AND object_id = :id")
                .param("id", id).query(Long.class).single();
        assertThat(audits).isEqualTo(3);
        call(delete("/api/v1/admin/collections/{id}", id), editor, "EDITOR", null).andExpect(status().isNotFound());
    }

    @Test
    void collectionValidationAndPermissions() throws Exception {
        call(post("/api/v1/admin/collections"), editor, "EDITOR",
                        collectionBody("Sai", "2030-01-02T00:00:00+07:00", "2030-01-01T00:00:00+07:00", published))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("visibleUntil"));
        call(post("/api/v1/admin/collections"), editor, "EDITOR", collectionBody("Sai", null, null, published, published))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("placeIds"));
        call(post("/api/v1/admin/collections"), editor, "EDITOR", collectionBody("Sai", null, null, UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        UUID avatar = jdbc.sql("""
                INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
                VALUES ('AVATAR', 'image/jpeg', 1, 'a.jpg') RETURNING id
                """).query(UUID.class).single();
        call(post("/api/v1/admin/collections"), editor, "EDITOR",
                        "{\"title\":\"Ảnh sai\",\"coverFileId\":\"%s\",\"placeIds\":[]}".formatted(avatar))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("coverFileId"));
        call(post("/api/v1/admin/collections"), reviewer, "REVIEWER", collectionBody("Không quyền", null, null))
                .andExpect(status().isForbidden());
        call(put("/api/v1/admin/collections/{id}", UUID.randomUUID()), editor, "EDITOR", collectionBody("Không có", null, null))
                .andExpect(status().isNotFound());
    }
}
