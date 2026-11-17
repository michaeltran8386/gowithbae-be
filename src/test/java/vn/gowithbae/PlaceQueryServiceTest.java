package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.ErrorResponseException;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.place.PlaceQueryServiceImpl;

/** PlaceQueryService do phần B cung cấp cho danh sách đã lưu và các module khác (RULE-01, PROP OQ-11). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "app.files.public-base-url=https://cdn.test/files",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
class PlaceQueryServiceTest {

    @Autowired PlaceQueryServiceImpl places;
    @Autowired JdbcClient jdbc;

    UUID published;
    UUID hidden;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE collection, place_image, place_tag, place_price, place, area CASCADE").update();
        jdbc.sql("INSERT INTO area (code, name) VALUES ('Q1', 'Quận 1')").update();
        published = place("P-PUB", "Nơi công khai", "PUBLISHED");
        hidden = place("P-HID", "Nơi đã ẩn", "HIDDEN");
        jdbc.sql("INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price) VALUES (:p, 'KNOWN', 1, 1, 1)")
                .param("p", hidden).update();
    }

    private UUID place(String code, String name, String status) {
        return jdbc.sql("""
                INSERT INTO place (code, name, category_id, area_id, address, lat, lng, status)
                SELECT :code, :name, (SELECT id FROM taxonomy WHERE code = 'CAFE'), (SELECT id FROM area WHERE code = 'Q1'),
                       'addr', 10.77, 106.70, :status
                RETURNING id
                """).param("code", code).param("name", name).param("status", status).query(UUID.class).single();
    }

    @Test
    void requirePublishedRejectsHiddenAndMissing() {
        places.requirePublished(published);
        assertThatThrownBy(() -> places.requirePublished(hidden))
                .isInstanceOf(ErrorResponseException.class)
                .satisfies(e -> assertThat(((ErrorResponseException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> places.requirePublished(UUID.randomUUID())).isInstanceOf(ErrorResponseException.class);
    }

    @Test
    void savedPlaceKeepsOnlyNameForUnavailablePlace() {
        var available = places.getSavedPlace(published);
        assertThat(available.available()).isTrue();
        assertThat(available.place().getAreaName()).isEqualTo("Quận 1");

        var gone = places.getSavedPlace(hidden);
        assertThat(gone.available()).isFalse();
        assertThat(gone.place().getName()).isEqualTo("Nơi đã ẩn");
        assertThat(gone.place().getPrice().getStatus()).isEqualTo(KnownStatus.UNKNOWN);
        assertThat(gone.place().getAreaName()).isNull();

        assertThatThrownBy(() -> places.getSavedPlace(UUID.randomUUID())).isInstanceOf(ErrorResponseException.class);
    }
}
