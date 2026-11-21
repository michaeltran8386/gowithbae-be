package vn.gowithbae;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Chỉ số chất lượng dữ liệu địa điểm (thiếu giá, thiếu giờ, quá hạn xác minh) trong báo cáo quản trị. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=",
        "DB_PASSWORD=", "app.places.verification-max-age-days=180"})
@AutoConfigureMockMvc
class PlaceStatisticsApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;

    String adminToken;
    UUID area;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE place_report, place_image, place_tag, place_price, place, area, app_user CASCADE").update();
        UUID admin = jdbc.sql("INSERT INTO app_user(email, display_name) VALUES ('owner@example.test', 'Owner') RETURNING id")
                .query(UUID.class).single();
        adminToken = "Bearer " + new TestJwtFactory(jdbc, encoder).token(admin, "SUPER_ADMIN");
        area = jdbc.sql("INSERT INTO area(code, name) VALUES ('Q1', 'Quận 1') RETURNING id").query(UUID.class).single();
        jdbc.sql("INSERT INTO area(code, name) VALUES ('Q3', 'Quận 3')").update();

        LocalDate fresh = LocalDate.now().minusDays(10);
        LocalDate stale = LocalDate.now().minusDays(400);
        // Đủ dữ liệu: giá đã biết, giờ đã biết, mới kiểm tra → không bị đếm.
        place("OK", "Q1", "PUBLISHED", "KNOWN", "KNOWN", fresh);
        // Chưa có dòng giá + giờ chưa rõ + chưa có ngày kiểm tra → đếm cả ba.
        place("NO-PRICE", "Q1", "PUBLISHED", null, "UNKNOWN", null);
        // Giá "chưa rõ" + kiểm tra quá hạn.
        place("UNKNOWN-PRICE", "Q1", "PUBLISHED", "UNKNOWN", "KNOWN", stale);
        // Nháp và khu vực khác: không tính khi lọc Q1; nháp không bao giờ tính.
        place("DRAFT", "Q1", "DRAFT", null, "UNKNOWN", null);
        place("OTHER-AREA", "Q3", "PUBLISHED", null, "UNKNOWN", null);
    }

    private void place(String code, String areaCode, String status, String priceStatus, String hoursStatus, LocalDate verifiedAt) {
        UUID id = jdbc.sql("""
                INSERT INTO place(code, name, category_id, area_id, address, lat, lng, status, hours_status, verified_at)
                SELECT :code, :code, (SELECT id FROM taxonomy WHERE code = 'CAFE' LIMIT 1), (SELECT id FROM area WHERE code = :area),
                       'addr', 10.7, 106.7, :status, :hours, :verified RETURNING id
                """).param("code", code).param("area", areaCode).param("status", status).param("hours", hoursStatus)
                .param("verified", verifiedAt).query(UUID.class).single();
        if (priceStatus != null) {
            jdbc.sql("""
                    INSERT INTO place_price(place_id, status, standard_for_two, min_price, max_price)
                    VALUES (:id, :s, CASE WHEN :s = 'KNOWN' THEN 300000 END, CASE WHEN :s = 'KNOWN' THEN 200000 END,
                            CASE WHEN :s = 'KNOWN' THEN 400000 END)
                    """).param("id", id).param("s", priceStatus).update();
        }
    }

    @Test
    void countsPublishedPlacesWithMissingPriceHoursOrStaleVerification() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", adminToken).param("areaId", area.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placesMissingPrice.value").value(2))
                .andExpect(jsonPath("$.placesMissingHours.value").value(1))
                .andExpect(jsonPath("$.placesVerificationOverdue.value").value(2));

        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placesMissingPrice.value").value(3))
                .andExpect(jsonPath("$.placesMissingHours.value").value(2));
    }

    @Test
    void platformFilterMakesPlaceMetricsUnavailableInsteadOfZero() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/summary").header("Authorization", adminToken).param("platform", "WEB_MOBILE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placesMissingPrice.value").isEmpty())
                .andExpect(jsonPath("$.placesMissingPrice.note").isNotEmpty());
    }
}
