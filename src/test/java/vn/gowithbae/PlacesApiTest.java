package vn.gowithbae;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Chạy Flyway trên Postgres thật (Testcontainers) và gọi API qua toàn bộ filter chain. Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@AutoConfigureMockMvc
class PlacesApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE place_tag, place_price, place, area CASCADE").update();
        jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1')").update();
        String insertPlace = """
                INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, status, verified_at)
                SELECT :code, :name, :search, (SELECT id FROM taxonomy WHERE code = 'CAFE'),
                       (SELECT id FROM area WHERE code = 'Q1'), 'addr', 10.77, 106.70, :status, current_date
                """;
        jdbc.sql(insertPlace).param("code", "P-001").param("name", "Cà phê Mèo").param("search", "ca phe meo")
                .param("status", "PUBLISHED").update();
        jdbc.sql(insertPlace).param("code", "P-002").param("name", "Gốm Nháp").param("search", "gom nhap")
                .param("status", "DRAFT").update();
        jdbc.sql(insertPlace).param("code", "P-003").param("name", "Rooftop Giá Chưa Rõ").param("search", "rooftop gia chua ro")
                .param("status", "PUBLISHED").update();
        jdbc.sql("""
                INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price)
                SELECT id, 'KNOWN', 200000, 150000, 300000 FROM place WHERE code = 'P-001'
                """).update();
        jdbc.sql("""
                INSERT INTO place_tag (place_id, taxonomy_id)
                SELECT p.id, t.id FROM place p, taxonomy t WHERE p.code = 'P-001' AND t.code IN ('CATS','COZY')
                """).update();
    }

    @Test
    void searchReturnsOnlyPublishedAndMatchesWithoutDiacritics() throws Exception {
        mvc.perform(get("/api/v1/places").param("q", "CÀ PHÊ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Cà phê Mèo"))
                .andExpect(jsonPath("$.items[0].price.standardForTwo").value(200000))
                .andExpect(jsonPath("$.items[0].tags.length()").value(2));
    }

    @Test
    void budgetFilterExcludesUnknownPriceAndRespectsBoundary() throws Exception {
        mvc.perform(get("/api/v1/places").param("budget", "FROM_200K_TO_400K"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Cà phê Mèo"));
        mvc.perform(get("/api/v1/places").param("budget", "UNDER_200K"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void priceAscPutsUnknownLast() throws Exception {
        mvc.perform(get("/api/v1/places").param("sort", "PRICE_ASC"))
                .andExpect(jsonPath("$.items[0].name").value("Cà phê Mèo"))
                .andExpect(jsonPath("$.items[1].price.status").value("UNKNOWN"));
    }

    @Test
    void unimplementedOperationReturns501AndPrivateRequiresAuth() throws Exception {
        mvc.perform(get("/api/v1/taxonomies")).andExpect(status().isNotImplemented());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/places")).andExpect(status().isUnauthorized());
    }
}
