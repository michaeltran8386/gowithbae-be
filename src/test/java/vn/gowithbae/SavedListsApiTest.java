package vn.gowithbae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.gowithbae.api.model.KnownStatus;
import vn.gowithbae.api.model.PlaceCard;
import vn.gowithbae.api.model.Price;
import vn.gowithbae.platform.PlaceQueryService;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.savedlist.SavedListsDelegate;

@Testcontainers(disabledWithoutDocker = true)
@Import({TestcontainersConfiguration.class, SavedListsApiTest.Places.class})
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="})
@AutoConfigureMockMvc
class SavedListsApiTest {
    private static final UUID PLACE = UUID.fromString("70616972-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired CurrentUser currentUser;
    @Autowired FakePlaces places;
    private UUID owner;
    private String token;
    private String otherToken;

    @TestConfiguration
    static class Places {
        @Bean @Primary FakePlaces savedListTestPlaces() { return new FakePlaces(); }
    }

    static class FakePlaces implements PlaceQueryService {
        boolean available = true;
        @Override public void requirePublished(UUID id) {
            if (!PLACE.equals(id) || !available) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Place not published");
            }
        }
        @Override public SavedPlace getSavedPlace(UUID id) {
            return new SavedPlace(new PlaceCard(id, "Deterministic cafe", new Price(KnownStatus.UNKNOWN), List.of()), available);
        }
    }

    @BeforeEach
    void seed() {
        owner = newUser();
        var jwt = new TestJwtFactory(jdbc, encoder);
        token = jwt.token(owner, "MEMBER");
        otherToken = jwt.token(newUser(), "MEMBER");
        places.available = true;
        // B-owned rows are fixtures only, required by the real saved_list_item foreign key.
        UUID area = jdbc.sql("INSERT INTO area(code, name) VALUES (:code, 'Test area') RETURNING id")
                .param("code", "SL-" + UUID.randomUUID()).query(UUID.class).single();
        jdbc.sql("INSERT INTO taxonomy(group_code,code,name) VALUES ('CATEGORY','SAVED_LIST_TEST','Test category') ON CONFLICT DO NOTHING").update();
        jdbc.sql("""
                INSERT INTO place(id, code, name, category_id, area_id, address, lat, lng, status)
                SELECT :id, :code, 'Deterministic cafe', id, :area, 'Test address', 10, 106, 'PUBLISHED'
                FROM taxonomy WHERE group_code = 'CATEGORY' AND code = 'SAVED_LIST_TEST'
                ON CONFLICT (id) DO NOTHING
                """).param("id", PLACE).param("code", "SL-" + PLACE).param("area", area).update();
    }

    private UUID newUser() {
        return jdbc.sql("INSERT INTO app_user(email, display_name) VALUES (:email, 'Test member') RETURNING id")
                .param("email", UUID.randomUUID() + "@saved.test").query(UUID.class).single();
    }

    private UUID create() throws Exception {
        mvc.perform(post("/api/v1/saved-lists").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Weekend  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Weekend"))
                .andExpect(jsonPath("$.placeCount").value(0));
        return jdbc.sql("SELECT id FROM saved_list WHERE owner_id = :owner").param("owner", owner).query(UUID.class).single();
    }

    @Test
    void emptyListAndRenameWorkAndOnlyOwnerSeesIt() throws Exception {
        UUID id = create();
        mvc.perform(get("/api/v1/saved-lists").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].isDefault").value(true)); // chỉ có danh sách "Đã lưu" tự tạo
        mvc.perform(get("/api/v1/saved-lists/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(patch("/api/v1/saved-lists/{id}", id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        mvc.perform(get("/api/v1/saved-lists").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].isDefault").value(true)).andExpect(jsonPath("$[1].name").value("Renamed"));
    }

    @Test
    void allForeignListOperationsReturn404() throws Exception {
        UUID id = create();
        for (var request : List.of(get("/api/v1/saved-lists/{id}", id), delete("/api/v1/saved-lists/{id}", id),
                patch("/api/v1/saved-lists/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Stolen\"}"),
                put("/api/v1/saved-lists/{id}/places/{place}", id, PLACE),
                delete("/api/v1/saved-lists/{id}/places/{place}", id, PLACE))) {
            mvc.perform(request.header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test
    void duplicatePutRetainsTimestampAndHiddenHistoryAndDeletionCascadesOnlyItems() throws Exception {
        UUID id = create();
        mvc.perform(put("/api/v1/saved-lists/{id}/places/{place}", id, PLACE).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        OffsetDateTime added = jdbc.sql("SELECT added_at FROM saved_list_item WHERE list_id = :id")
                .param("id", id).query(OffsetDateTime.class).single();
        places.available = false;
        mvc.perform(put("/api/v1/saved-lists/{id}/places/{place}", id, PLACE).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertEquals(added, jdbc.sql("SELECT added_at FROM saved_list_item WHERE list_id = :id")
                .param("id", id).query(OffsetDateTime.class).single());
        mvc.perform(get("/api/v1/saved-lists/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.placeCount").value(1))
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.items[0].place.name").value("Deterministic cafe"));
        mvc.perform(delete("/api/v1/saved-lists/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM saved_list_item WHERE list_id = :id").param("id", id).query(Long.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM place WHERE id = :id").param("id", PLACE).query(Long.class).single());
        mvc.perform(get("/api/v1/saved-lists/{id}", id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    void removalIsIdempotentAndUnpublishedNewSaveIsRejected() throws Exception {
        UUID id = create();
        mvc.perform(put("/api/v1/saved-lists/{id}/places/{place}", id, PLACE).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/v1/saved-lists/{id}/places/{place}", id, PLACE).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
        }
        places.available = false;
        mvc.perform(put("/api/v1/saved-lists/{id}/places/{place}", id, PLACE).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void namesMustNotBeBlankOrExceedContractLimit() throws Exception {
        UUID id = create();
        for (String name : List.of("", "   ", "x".repeat(101))) {
            String body = "{\"name\":\"" + name + "\"}";
            mvc.perform(post("/api/v1/saved-lists").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            mvc.perform(patch("/api/v1/saved-lists/{id}", id).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/saved-lists")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingBProviderOnlyBlocksOperationsThatNeedPlaces() throws Exception {
        UUID id = create();
        var delegate = new SavedListsDelegate(jdbc, currentUser,
                new StaticListableBeanFactory().getBeanProvider(PlaceQueryService.class));
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(decoder.decode(token),
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))));
        SecurityContextHolder.setContext(context);
        try {
            assertEquals(0, delegate.getSavedList(id).getBody().getItems().size());
            var missing = assertThrows(ResponseStatusException.class, () -> delegate.addPlaceToList(id, PLACE));
            assertEquals(HttpStatus.NOT_IMPLEMENTED, missing.getStatusCode());
            jdbc.sql("INSERT INTO saved_list_item(list_id, place_id) VALUES (:id, :place)")
                    .param("id", id).param("place", PLACE).update();
            assertEquals(HttpStatus.NO_CONTENT, delegate.addPlaceToList(id, PLACE).getStatusCode());
            assertEquals(HttpStatus.NOT_IMPLEMENTED,
                    assertThrows(ResponseStatusException.class, () -> delegate.getSavedList(id)).getStatusCode());
            assertEquals(HttpStatus.NO_CONTENT, delegate.removePlaceFromList(id, PLACE).getStatusCode());
            assertEquals(0, delegate.getSavedList(id).getBody().getItems().size());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
