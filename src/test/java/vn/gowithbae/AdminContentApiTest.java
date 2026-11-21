package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.gowithbae.upload.LocalFileStorage;

/** Nội dung trang tĩnh, ảnh công khai, thông báo do admin gửi. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=",
        "DB_PASSWORD=", "app.files.root=target/test-uploads"})
@AutoConfigureMockMvc
class AdminContentApiTest {

    static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0 };

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired LocalFileStorage storage;

    UUID admin;
    UUID member;
    String adminToken;
    String memberToken;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE notification, audit_log, collection, place_image, place_tag, place_price, place, area, stored_file, app_user CASCADE").update();
        admin = user("owner@example.test");
        member = user("member@example.test");
        var tokens = new TestJwtFactory(jdbc, encoder);
        adminToken = "Bearer " + tokens.token(admin, "SUPER_ADMIN");
        memberToken = "Bearer " + tokens.token(member, "MEMBER");
    }

    private UUID user(String email) {
        return jdbc.sql("INSERT INTO app_user(email, display_name) VALUES (:e, :e) RETURNING id").param("e", email)
                .query(UUID.class).single();
    }

    private static String siteJson(String title, boolean bannerOn, String bannerText, String link) {
        return """
                {"homeHero":{"title":"%s","subtitle":"Phụ đề mới"},
                 "homeBanner":{"enabled":%s,"text":"%s","linkUrl":%s},
                 "faq":[{"question":"Hỏi 1?","answer":"Đáp 1."},{"question":"Hỏi 2?","answer":"Đáp 2."}],
                 "terms":{"body":"Điều khoản."},"privacy":{"body":"Quyền riêng tư."},
                 "contact":{"email":"hello@gowithbae.test","phone":"0280000000","address":null,"facebook":null},
                 "footer":{"text":"© 2026 GoWithBae"}}
                """.formatted(title, bannerOn, bannerText, link == null ? "null" : "\"" + link + "\"");
    }

    // ───────────── Nội dung trang tĩnh ─────────────

    @Test
    void siteContentIsPublicAndEditableBySuperAdminOnly() throws Exception {
        mvc.perform(get("/api/v1/site-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeHero.title").exists())
                .andExpect(jsonPath("$.faq").isArray());

        mvc.perform(put("/api/v1/admin/site-content").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(siteJson("Hẹn hò dễ hơn", true, "Giảm 20% cuối tuần", "/collections")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeHero.title").value("Hẹn hò dễ hơn"))
                .andExpect(jsonPath("$.updatedBy").value(admin.toString()));

        mvc.perform(get("/api/v1/site-content"))
                .andExpect(jsonPath("$.homeHero.title").value("Hẹn hò dễ hơn"))
                .andExpect(jsonPath("$.homeBanner.enabled").value(true))
                .andExpect(jsonPath("$.faq.length()").value(2))
                .andExpect(jsonPath("$.contact.email").value("hello@gowithbae.test"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'SITE_CONTENT_UPDATED'").query(Long.class).single())
                .isEqualTo(1L);

        // Gửi lại y hệt: không có mục nào đổi, không ghi audit thêm.
        mvc.perform(put("/api/v1/admin/site-content").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(siteJson("Hẹn hò dễ hơn", true, "Giảm 20% cuối tuần", "/collections"))).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'SITE_CONTENT_UPDATED'").query(Long.class).single())
                .isEqualTo(1L);

        mvc.perform(put("/api/v1/admin/site-content").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content(siteJson("x", false, "", null))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/site-content")).andExpect(status().isUnauthorized());
    }

    @Test
    void siteContentValidatesBanner() throws Exception {
        mvc.perform(put("/api/v1/admin/site-content").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(siteJson("Tiêu đề", true, "Khuyến mãi", "javascript:alert(1)")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("homeBanner.linkUrl"));
        mvc.perform(put("/api/v1/admin/site-content").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(siteJson("Tiêu đề", true, "  ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("homeBanner.text"));
    }

    // ───────────── Ảnh công khai ─────────────

    private UUID file(String purpose, boolean writeBytes) throws Exception {
        String key = UUID.randomUUID().toString();
        if (writeBytes) {
            storage.write(key, PNG);
        }
        return jdbc.sql("""
                INSERT INTO stored_file(owner_user_id, purpose, content_type, size_bytes, storage_key)
                VALUES (:owner, :purpose, 'image/png', :size, :key) RETURNING id
                """).param("owner", admin).param("purpose", purpose).param("size", PNG.length).param("key", key)
                .query(UUID.class).single();
    }

    @Test
    void onlyAttachedPlaceImagesAndCollectionCoversAreServed() throws Exception {
        jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1')").update();
        UUID place = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, status)
                SELECT 'P-IMG', 'Có ảnh', (SELECT id FROM taxonomy WHERE code = 'CAFE'), (SELECT id FROM area WHERE code = 'Q1'),
                       'addr', 10.7, 106.7, 'DRAFT' RETURNING id
                """).query(UUID.class).single();
        UUID attached = file("PLACE_IMAGE", true);
        jdbc.sql("INSERT INTO place_image(place_id, file_id, is_cover, source_rights) VALUES (:p, :f, true, 'Tự chụp')")
                .param("p", place).param("f", attached).update();
        UUID cover = file("COLLECTION", true);
        jdbc.sql("INSERT INTO collection(title, cover_file_id) VALUES ('Bộ sưu tập', :f)").param("f", cover).update();

        mvc.perform(get("/api/v1/files/{id}", attached))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/v1/files/{id}", cover)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/files/{id}", file("PLACE_IMAGE", true))).andExpect(status().isNotFound()); // chưa gắn
        mvc.perform(get("/api/v1/files/{id}", file("AVATAR", true))).andExpect(status().isNotFound());
        UUID missingOnDisk = file("PLACE_IMAGE", false);
        jdbc.sql("INSERT INTO place_image(place_id, file_id, source_rights) VALUES (:p, :f, 'Tự chụp')")
                .param("p", place).param("f", missingOnDisk).update();
        mvc.perform(get("/api/v1/files/{id}", missingOnDisk)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/{id}", UUID.randomUUID())).andExpect(status().isNotFound());

        // API địa điểm trả URL tương đối trỏ về endpoint này.
        mvc.perform(get("/api/v1/admin/places/{id}", place).header("Authorization", adminToken))
                .andExpect(jsonPath("$.images[0].url").value("/api/v1/files/" + attached));
    }

    // ───────────── Thông báo ─────────────

    @Test
    void adminSendsAnnouncementsToEveryoneOrOneUser() throws Exception {
        UUID locked = user("locked@example.test");
        jdbc.sql("UPDATE app_user SET status = 'LOCKED' WHERE id = :id").param("id", locked).update();

        mvc.perform(post("/api/v1/admin/notifications").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Bảo trì tối nay 23:00\",\"target\":\"ALL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipients").value(2));
        mvc.perform(get("/api/v1/notifications").header("Authorization", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("ADMIN_ANNOUNCEMENT"))
                .andExpect(jsonPath("$.items[0].summary").value("Bảo trì tối nay 23:00"));
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE recipient_id = :id").param("id", locked)
                .query(Long.class).single()).isZero();

        mvc.perform(post("/api/v1/admin/notifications").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Chào bạn!\",\"target\":\"USER\",\"userId\":\"" + member + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipients").value(1));
        mvc.perform(post("/api/v1/admin/notifications").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"Thiếu người nhận\",\"target\":\"USER\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/notifications").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"Bị khóa\",\"target\":\"USER\",\"userId\":\"" + locked + "\"}")).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/admin/notifications/sent").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].summary").value("Chào bạn!"))
                .andExpect(jsonPath("$.items[0].userId").value(member.toString()))
                .andExpect(jsonPath("$.items[1].target").value("ALL"));

        mvc.perform(post("/api/v1/admin/notifications").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"x\",\"target\":\"ALL\"}")).andExpect(status().isForbidden());
    }
}
