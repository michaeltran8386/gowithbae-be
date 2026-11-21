package vn.gowithbae;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Lọc nhãn (RULE-02), giờ mở cửa (RULE-05), vị trí (OQ-05), chi tiết và so sánh (FR-05/06). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "app.files.public-base-url=https://cdn.test/files",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@AutoConfigureMockMvc
class PlaceFiltersApiTest {

    /** Một Thứ Tư cố định ở tương lai để ngoại lệ ngày luôn nằm sau "hôm nay". */
    static final LocalDate WED = LocalDate.of(2030, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    UUID cafe;
    UUID pottery;
    UUID bar;
    UUID unknownHours;
    UUID hidden;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE collection, place_image, place_tag, place_price, opening_hours, date_exception, place, area, stored_file CASCADE").update();
        jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1')").update();

        cafe = place("P-CAFE", "Cà phê Mèo", 10.7769, 106.7009, "KNOWN", "PUBLISHED");
        pottery = place("P-GOM", "Xưởng Gốm", 10.7840, 106.6880, "KNOWN", "PUBLISHED");
        bar = place("P-BAR", "Bar Đêm", 10.8030, 106.7140, "KNOWN", "PUBLISHED");
        unknownHours = place("P-UNK", "Quán giờ chưa rõ", 10.7900, 106.7500, "UNKNOWN", "PUBLISHED");
        hidden = place("P-HID", "Quán bị ẩn", 10.7770, 106.7010, "KNOWN", "HIDDEN");

        tags(cafe, "CAFE", "QUIET", "COZY", "CATS");
        tags(pottery, "POTTERY", "QUIET");
        tags(bar, "GAMES", "LIVELY");
        tags(unknownHours, "CAFE", "QUIET");
        tags(hidden, "CAFE");

        for (int dow = 1; dow <= 7; dow++) {
            hours(cafe, dow, "07:00", "22:00");
            hours(bar, dow, "18:00", "02:00"); // qua đêm
            hours(hidden, dow, "07:00", "22:00");
            if (dow != 1) {
                hours(pottery, dow, "09:00", "18:00"); // nghỉ Thứ Hai
            }
        }
        // Xưởng gốm nghỉ Thứ Tư tuần sau; quán mèo đổi giờ Thứ Tư hai tuần sau.
        jdbc.sql("INSERT INTO date_exception (place_id, on_date, closed, note) VALUES (:p, :d, true, 'Nghỉ')")
                .param("p", pottery).param("d", WED.plusDays(7)).update();
        jdbc.sql("INSERT INTO date_exception (place_id, on_date, closed, opens_at, closes_at) VALUES (:p, :d, false, '12:00', '13:00')")
                .param("p", cafe).param("d", WED.plusDays(14)).update();

        jdbc.sql("""
                INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price)
                VALUES (:p, 'KNOWN', 150000, 100000, 200000)
                """).param("p", cafe).update();
        UUID file = jdbc.sql("""
                INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
                VALUES ('PLACE_IMAGE', 'image/jpeg', 10, 'places/cafe.jpg') RETURNING id
                """).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO place_image (place_id, file_id, sort_order, is_cover, caption, source_rights)
                VALUES (:p, :f, 0, true, 'Mặt tiền', 'Ảnh tự chụp')
                """).param("p", cafe).param("f", file).update();
    }

    private UUID place(String code, String name, double lat, double lng, String hoursStatus, String status) {
        return jdbc.sql("""
                INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, hours_status, status,
                                   description, verified_at)
                SELECT :code, :name, lower(:code), (SELECT id FROM taxonomy WHERE code = 'CAFE'),
                       (SELECT id FROM area WHERE code = 'Q1'), 'Địa chỉ', :lat, :lng, :hs, :status, 'Mô tả', current_date
                RETURNING id
                """)
                .param("code", code).param("name", name).param("lat", lat).param("lng", lng)
                .param("hs", hoursStatus).param("status", status)
                .query(UUID.class).single();
    }

    private void tags(UUID place, String... codes) {
        for (String code : codes) {
            jdbc.sql("INSERT INTO place_tag (place_id, taxonomy_id) SELECT :p, id FROM taxonomy WHERE code = :c")
                    .param("p", place).param("c", code).update();
        }
    }

    private void hours(UUID place, int dow, String opens, String closes) {
        jdbc.sql("INSERT INTO opening_hours (place_id, day_of_week, opens_at, closes_at) VALUES (:p, :d, CAST(:o AS time), CAST(:c AS time))")
                .param("p", place).param("d", dow).param("o", opens).param("c", closes).update();
    }

    private String tagId(String code) {
        return jdbc.sql("SELECT id FROM taxonomy WHERE code = :c").param("c", code).query(UUID.class).single().toString();
    }

    private static String at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(VN).toOffsetDateTime().toString();
    }

    private ResultActions expectNames(ResultActions r, String... names) throws Exception {
        return r.andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(names.length))
                .andExpect(jsonPath("$.items[*].name", containsInAnyOrder((Object[]) names)));
    }

    // ───────────── RULE-02 ─────────────

    @Test
    void tagsInsideActivityGroupAreOr() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("tagIds", tagId("CAFE"), tagId("POTTERY"))),
                "Cà phê Mèo", "Xưởng Gốm", "Quán giờ chưa rõ");
    }

    @Test
    void tagsInsideVibeGroupAreAnd() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("tagIds", tagId("QUIET"), tagId("COZY"))), "Cà phê Mèo");
    }

    @Test
    void groupsCombineWithAnd() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("tagIds", tagId("CAFE"), tagId("QUIET"))),
                "Cà phê Mèo", "Quán giờ chưa rõ");
        expectNames(mvc.perform(get("/api/v1/places").param("tagIds", tagId("GAMES"), tagId("QUIET"))));
    }

    @Test
    void unknownTagIs400() throws Exception {
        mvc.perform(get("/api/v1/places").param("tagIds", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("tagIds"));
    }

    // ───────────── RULE-05 / OQ-04 ─────────────

    @Test
    void openAtExcludesUnknownHoursAndClosedPlaces() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED, "10:00"))), "Cà phê Mèo", "Xưởng Gốm");
    }

    @Test
    void closingTimeIsExclusiveAndOvernightOpensSameEvening() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED, "22:00"))), "Bar Đêm");
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED, "21:59"))), "Cà phê Mèo", "Bar Đêm");
    }

    @Test
    void overnightRangeCarriesIntoNextMorning() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED.plusDays(1), "01:30"))), "Bar Đêm");
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED.plusDays(1), "02:00"))));
    }

    @Test
    void weeklyDayOffIsRespected() throws Exception {
        LocalDate monday = WED.minusDays(2);
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(monday, "10:00"))), "Cà phê Mèo");
    }

    @Test
    void dateExceptionOverridesWeeklySchedule() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED.plusDays(7), "10:00"))), "Cà phê Mèo");
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED.plusDays(14), "10:00"))), "Xưởng Gốm");
        expectNames(mvc.perform(get("/api/v1/places").param("at", at(WED.plusDays(14), "12:30"))), "Cà phê Mèo", "Xưởng Gốm");
    }

    @Test
    void atIsInterpretedInVietnamTime() throws Exception {
        // 03:00Z = 10:00 giờ Việt Nam.
        expectNames(mvc.perform(get("/api/v1/places").param("at", WED + "T03:00:00Z")), "Cà phê Mèo", "Xưởng Gốm");
    }

    // ───────────── Vị trí / OQ-05 ─────────────

    @Test
    void radiusFiltersByStraightLineDistance() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("lat", "10.7769").param("lng", "106.7009").param("radiusKm", "2")),
                "Cà phê Mèo", "Xưởng Gốm");
    }

    @Test
    void nearestSortsByDistanceAndReturnsDistance() throws Exception {
        mvc.perform(get("/api/v1/places").param("lat", "10.7769").param("lng", "106.7009").param("sort", "NEAREST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Cà phê Mèo"))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.0))
                .andExpect(jsonPath("$.items[1].name").value("Xưởng Gốm"))
                .andExpect(jsonPath("$.items[1].distanceKm").value(1.6))
                .andExpect(jsonPath("$.items[2].name").value("Bar Đêm"))
                .andExpect(jsonPath("$.items[3].name").value("Quán giờ chưa rõ"));
    }

    @Test
    void distanceIsNullWithoutOrigin() throws Exception {
        mvc.perform(get("/api/v1/places").param("q", "p-cafe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].distanceKm").doesNotExist())
                .andExpect(jsonPath("$.items[0].coverUrl").value(matchesPattern("https://cdn\\.test/files/[0-9a-f-]{36}")));
    }

    @Test
    void invalidOriginCombinationsAre400() throws Exception {
        mvc.perform(get("/api/v1/places").param("lat", "10.7")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places").param("radiusKm", "3")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places").param("sort", "NEAREST")).andExpect(status().isBadRequest());
    }

    @Test
    void noMatchStill200() throws Exception {
        expectNames(mvc.perform(get("/api/v1/places").param("q", "khong co noi nao nhu vay")));
    }

    // ───────────── Chi tiết / so sánh ─────────────

    @Test
    void placeDetailHasFullProfile() throws Exception {
        mvc.perform(get("/api/v1/places/{id}", cafe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("P-CAFE"))
                .andExpect(jsonPath("$.category.code").value("CAFE"))
                .andExpect(jsonPath("$.area.name").value("Quận 1"))
                .andExpect(jsonPath("$.price.standardForTwo").value(150000))
                .andExpect(jsonPath("$.tags.length()").value(4))
                .andExpect(jsonPath("$.images[0].url").value(matchesPattern("https://cdn\\.test/files/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.images[0].cover").value(true))
                .andExpect(jsonPath("$.hours.status").value("KNOWN"))
                .andExpect(jsonPath("$.hours.weekly.length()").value(7))
                .andExpect(jsonPath("$.hours.weekly[0].ranges[0].opensAt").value("07:00"))
                .andExpect(jsonPath("$.hours.exceptions[0].ranges[0].opensAt").value("12:00"))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.distanceKm").value(nullValue()));
    }

    @Test
    void unknownHoursAndPriceAreReportedAsUnknown() throws Exception {
        mvc.perform(get("/api/v1/places/{id}", unknownHours))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.hours.weekly.length()").value(0))
                .andExpect(jsonPath("$.price.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.price.standardForTwo").value(nullValue()));
    }

    @Test
    void hiddenOrMissingPlaceIs404() throws Exception {
        mvc.perform(get("/api/v1/places/{id}", hidden)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/places/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void compareKeepsRequestedOrderAndAddsDistance() throws Exception {
        mvc.perform(get("/api/v1/places/compare").param("ids", pottery + "," + cafe)
                        .param("lat", "10.7769").param("lng", "106.7009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Xưởng Gốm"))
                .andExpect(jsonPath("$[0].distanceKm").value(1.6))
                .andExpect(jsonPath("$[1].name").value("Cà phê Mèo"));
    }

    @Test
    void compareRejectsWrongCountDuplicatesAndHiddenPlaces() throws Exception {
        mvc.perform(get("/api/v1/places/compare").param("ids", cafe.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ids"));
        mvc.perform(get("/api/v1/places/compare").param("ids", cafe + "," + cafe)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/compare").param("ids", cafe + "," + pottery + "," + bar + "," + unknownHours))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/compare").param("ids", cafe + "," + hidden)).andExpect(status().isNotFound());
    }
}
