package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

import vn.gowithbae.admin.imports.ImportRowValidator;

/** Nhập địa điểm bằng CSV: kiểm tra thử, báo lỗi dòng, commit toàn lô (FR-30, RULE-23, PROP OQ-19). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=" + TestJwt.SECRET,
        "app.files.public-base-url=https://cdn.test/files",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@AutoConfigureMockMvc
class AdminImportsApiTest {

    static final String HEADER = String.join(",", ImportRowValidator.COLUMNS);

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    UUID editor;
    UUID support;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE audit_log, import_row_result, import_batch, collection, place_image, place_tag, place_price, opening_hours, date_exception, place, area, stored_file, app_user CASCADE").update();
        editor = jdbc.sql("INSERT INTO app_user (email, display_name) VALUES ('editor@test.vn', 'Editor') RETURNING id")
                .query(UUID.class).single();
        support = jdbc.sql("INSERT INTO app_user (email, display_name) VALUES ('support@test.vn', 'Support') RETURNING id")
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO taxonomy (group_code, code, name) VALUES ('CATEGORY', 'TEST_CAT_CAFE', 'Quán cà phê') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1')").update();
    }

    /** Một dòng theo đúng thứ tự cột mẫu; trường có dấu phẩy được bọc ngoặc kép. */
    private static String row(String code, String name, String category, String area, String lat, String lng,
            String tags, String priceStatus, String std, String min, String max) {
        return String.join(",", code, name, category, area, "\"12 Đường Thử, Quận 1\"", lat, lng,
                "Mô tả", "0280000000", "https://quan.test", tags, priceStatus, std, min, max, "2 đồ uống", "Menu",
                "Khảo sát", "2026-11-01");
    }

    private ResultActions upload(UUID actor, String role, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/admin/imports")
                .file(new MockMultipartFile("file", "places.csv", "text/csv", bytes))
                .header("Authorization", TestJwt.bearer(actor, role)));
    }

    private ResultActions upload(String csv) throws Exception {
        return upload(editor, "EDITOR", csv.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions commit(String batchId) throws Exception {
        return mvc.perform(post("/api/v1/admin/imports/{id}/commit", batchId)
                .header("Authorization", TestJwt.bearer(editor, "EDITOR")));
    }

    private long places() {
        return jdbc.sql("SELECT count(*) FROM place").query(Long.class).single();
    }

    @Test
    void templateHasAllColumnsAndRequiresEditor() throws Exception {
        mvc.perform(get("/api/v1/admin/imports/template.csv").header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString(HEADER)));
        mvc.perform(get("/api/v1/admin/imports/template.csv").header("Authorization", TestJwt.bearer(support, "SUPPORT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void validBatchWithBomValidatesThenCommitsAsDrafts() throws Exception {
        String csv = "﻿" + HEADER + "\r\n"
                + row("CSV-001", "Quán Một", "TEST_CAT_CAFE", "Q1", "10.7769", "106.7009", "CAFE;VIBE:QUIET", "KNOWN", "200000", "150000", "300000") + "\r\n"
                + row("CSV-002", "Quán Hai", "TEST_CAT_CAFE", "Q1", "10.7800", "106.7100", "", "UNKNOWN", "", "", "") + "\r\n";
        String json = upload(csv)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.templateVersion").value("v1"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.rows[0].placeCode").value("CSV-001"))
                .andReturn().getResponse().getContentAsString();
        String batchId = JsonPath.read(json, "$.id");
        assertThat(places()).as("kiểm tra thử chưa ghi địa điểm").isZero();

        commit(batchId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.committedAt").exists());

        assertThat(jdbc.sql("SELECT count(*) FROM place WHERE status = 'DRAFT' AND created_by = :e")
                .param("e", editor).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM place_tag pt JOIN place p ON p.id = pt.place_id WHERE p.code = 'CSV-001'
                """).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT pp.standard_for_two FROM place_price pp JOIN place p ON p.id = pp.place_id WHERE p.code = 'CSV-001'
                """).query(Long.class).single()).isEqualTo(200000L);
        assertThat(jdbc.sql("SELECT search_text FROM place WHERE code = 'CSV-001'").query(String.class).single())
                .contains("quan mot", "quan 1");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'PLACE_CREATE' AND reason LIKE 'Nhập CSV lô%'")
                .query(Long.class).single()).isEqualTo(2);

        commit(batchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_ALREADY_COMMITTED"));
    }

    @Test
    void invalidRowsAreReportedAndBatchCannotBeCommitted() throws Exception {
        jdbc.sql("""
                INSERT INTO place (code, name, category_id, area_id, address, lat, lng)
                SELECT 'CSV-OLD', 'Có sẵn', (SELECT id FROM taxonomy WHERE code = 'TEST_CAT_CAFE'),
                       (SELECT id FROM area WHERE code = 'Q1'), 'addr', 10.7, 106.7
                """).update();
        String csv = HEADER + "\n"
                + row("CSV-010", "Hợp lệ", "TEST_CAT_CAFE", "Q1", "10.7", "106.7", "", "", "", "", "") + "\n"
                + row("CSV-010", "Trùng trong file", "TEST_CAT_CAFE", "Q1", "10.7", "106.7", "", "", "", "", "") + "\n"
                + row("CSV-OLD", "Trùng DB", "TEST_CAT_CAFE", "Q1", "10.7", "106.7", "", "", "", "", "") + "\n"
                + row("CSV-011", "Tọa độ sai", "NO_SUCH_CAT", "Q1", "200", "abc", "NOPE", "KNOWN", "100000", "200000", "300000") + "\n";
        String json = upload(csv)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.errorRows").value(3))
                .andExpect(jsonPath("$.rows[0].valid").value(true))
                .andExpect(jsonPath("$.rows[1].errors", hasItem("Mã chi nhánh trùng với dòng 2")))
                .andExpect(jsonPath("$.rows[2].errors", hasItem("Mã chi nhánh đã tồn tại trong hệ thống")))
                .andExpect(jsonPath("$.rows[3].errors.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        String batchId = JsonPath.read(json, "$.id");

        mvc.perform(get("/api/v1/admin/imports/{id}", batchId).header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(4));
        commit(batchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RULE-23"));
        assertThat(places()).isEqualTo(1);
    }

    @Test
    void staleBatchIsRejectedAtCommitWithoutPartialWrites() throws Exception {
        String csv = HEADER + "\n"
                + row("CSV-020", "Một", "TEST_CAT_CAFE", "Q1", "10.7", "106.7", "", "", "", "", "") + "\n"
                + row("CSV-021", "Hai", "TEST_CAT_CAFE", "Q1", "10.7", "106.7", "", "", "", "", "") + "\n";
        String batchId = JsonPath.read(upload(csv).andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("""
                INSERT INTO place (code, name, category_id, area_id, address, lat, lng)
                SELECT 'CSV-021', 'Tạo sau', (SELECT id FROM taxonomy WHERE code = 'TEST_CAT_CAFE'),
                       (SELECT id FROM area WHERE code = 'Q1'), 'addr', 10.7, 106.7
                """).update();
        commit(batchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
        assertThat(jdbc.sql("SELECT count(*) FROM place WHERE code = 'CSV-020'").query(Long.class).single()).isZero();
    }

    @Test
    void fileLevelProblemsAre400WithoutCreatingBatch() throws Exception {
        upload("code,name\nA,B\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].message", hasItem("Thiếu cột category_code")));
        upload(HEADER + "\n").andExpect(status().isBadRequest());
        byte[] latin1 = (HEADER + "\n" + row("CSV-030", "Quán Ă", "TEST_CAT_CAFE", "Q1", "10.7", "106.7", "", "", "", "", ""))
                .getBytes(StandardCharsets.ISO_8859_1);
        upload(editor, "EDITOR", latin1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("File phải mã hóa UTF-8"));
        upload(support, "SUPPORT", (HEADER + "\n").getBytes(StandardCharsets.UTF_8)).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM import_batch").query(Long.class).single()).isZero();
        mvc.perform(get("/api/v1/admin/imports/{id}", UUID.randomUUID()).header("Authorization", TestJwt.bearer(editor, "EDITOR")))
                .andExpect(status().isNotFound());
    }
}
