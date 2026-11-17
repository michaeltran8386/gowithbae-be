package vn.gowithbae;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.gowithbae.api.UploadsApiController;
import vn.gowithbae.common.ApiExceptionHandler;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.FileService;
import vn.gowithbae.platform.RequestRateLimiter;
import vn.gowithbae.upload.LocalFileStorage;
import vn.gowithbae.upload.UploadsDelegate;

class UploadsApiTest {
    @TempDir Path root;
    JdbcClient jdbc;
    JdbcClient.StatementSpec statement;
    CurrentUser user;
    RequestRateLimiter limiter;
    UploadsDelegate delegate;
    MockMvc mvc;

    static byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }

    @BeforeEach void setup() {
        jdbc = mock(JdbcClient.class);
        statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);
        user = mock(CurrentUser.class);
        when(user.optionalId()).thenReturn(Optional.empty());
        limiter = mock(RequestRateLimiter.class);
        when(limiter.consume(anyString(), anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        delegate = new UploadsDelegate(jdbc, user, limiter, new ServletWebRequest(new MockHttpServletRequest()),
                new LocalFileStorage(root.toString()), manager, 1024, 20);
        mvc = MockMvcBuilders.standaloneSetup(new UploadsApiController(delegate))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void realPngWithTraversalNameIsStoredPrivately() throws Exception {
        byte[] png = image("png");
        mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", "../../escape.png", "image/png", png))
                .param("purpose", "PLACE_REPORT"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.url").isEmpty())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.storage_key").doesNotExist());
        try (var files = Files.list(root)) {
            var stored = files.toList();
            assertEquals(1, stored.size());
            UUID.fromString(stored.getFirst().getFileName().toString());
            assertArrayEquals(png, Files.readAllBytes(stored.getFirst()));
        }
    }

    @Test void jpegAcceptedAndCorruptSpoofSvgAndTruncationRejected() throws Exception {
        assertEquals(HttpStatus.CREATED, delegate.uploadFile(new MockMultipartFile("file", "x", "image/jpeg", image("jpeg")), "PLACE_REPORT").getStatusCode());
        byte[] png = image("png"), jpeg = image("jpeg");
        for (var file : new MockMultipartFile[]{
                new MockMultipartFile("file", "x", "image/jpeg", png),
                new MockMultipartFile("file", "x", "image/png", new byte[]{1, 2, 3}),
                new MockMultipartFile("file", "x", "image/svg+xml", "<svg/>".getBytes()),
                new MockMultipartFile("file", "x", "image/png", Arrays.copyOf(png, png.length - 12)),
                new MockMultipartFile("file", "x", "image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 2))}) {
            var error = assertThrows(ResponseStatusException.class, () -> delegate.uploadFile(file, "PLACE_REPORT"));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
    }

    @Test void anonymousAvatarForbiddenOversizeAndInvalidPurposeRejected() throws Exception {
        mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", image("png")))
                .param("purpose", "AVATAR")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", new byte[1025]))
                .param("purpose", "PLACE_REPORT")).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", image("png")))
                .param("purpose", "OTHER")).andExpect(status().isBadRequest());
    }

    @Test void rateLimitUsesRemotePeerAndRejectsBeforeStorage() throws Exception {
        when(limiter.consume(anyString(), anyString(), anyInt(), any())).thenReturn(false);
        mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", image("png")))
                .header("X-Forwarded-For", "spoofed").param("purpose", "PLACE_REPORT"))
                .andExpect(status().isTooManyRequests());
        verify(limiter).consume(eq("uploads-peer"), eq("127.0.0.1"), eq(20), eq(Duration.ofMinutes(1)));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void databaseFailureRemovesContent() throws Exception {
        when(statement.update()).thenThrow(new IllegalStateException("DB failure"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, assertThrows(ResponseStatusException.class,
                () -> delegate.uploadFile(new MockMultipartFile("file", "x", "image/png", image("png")), "PLACE_REPORT")).getStatusCode());
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void excessiveDimensionsAndBadPngCrcRejectedBeforeDecode() throws Exception {
        byte[] png = image("png");
        java.nio.ByteBuffer.wrap(png, 16, 4).putInt(100_000);
        var crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        java.nio.ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> delegate.uploadFile(new MockMultipartFile("file", "x", "image/png", png), "PLACE_REPORT")).getStatusCode());
        png[29] ^= 1;
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> delegate.uploadFile(new MockMultipartFile("file", "x", "image/png", png), "PLACE_REPORT")).getStatusCode());
    }

    @Test void missingForeignAndAnonymousMetadataIs404() {
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<FileService.FileMetadata> query = mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<FileService.FileMetadata>>any())).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());
        var files = new FileService(jdbc);
        UUID id = UUID.randomUUID(), foreign = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> files.requireOwned(id, foreign, "AVATAR")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> files.openOwned(id, foreign, "AVATAR")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> files.requireOwned(id, null, "PLACE_REPORT")).getStatusCode());
    }
}

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=",
        "spring.config.import=classpath:application-uploads.yml"})
@AutoConfigureMockMvc
class UploadsDatabaseTest {
    @TempDir static Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.files.root", () -> root.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired FileService files;

    @Test void persistedJwtUploadOwnershipPurposeAndAnonymousIsolation() throws Exception {
        UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id, email, display_name) VALUES (:id, :email, 'Upload test')")
                .param("id", owner).param("email", owner + "@test.invalid").update();
        String token = new TestJwtFactory(jdbc, encoder).token(owner, "MEMBER");
        byte[] png = UploadsApiTest.image("png");
        String response = mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", "../x.png", "image/png", png))
                .param("purpose", "AVATAR").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
        assertEquals(owner, files.requireOwned(id, owner, "AVATAR").ownerUserId());
        try (var input = files.openOwned(id, owner, "AVATAR")) { assertArrayEquals(png, input.readAllBytes()); }
        for (Runnable check : new Runnable[]{() -> files.requireOwned(id, foreign, "AVATAR"),
                () -> files.openOwned(id, foreign, "AVATAR"), () -> files.requireOwned(id, owner, "SUPPORT")}) {
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, check::run).getStatusCode());
        }
        mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", "x", "image/png", png))
                .param("purpose", "PLACE_IMAGE").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        String anonymous = mvc.perform(multipart("/api/v1/uploads").file(new MockMultipartFile("file", "x", "image/png", png))
                .param("purpose", "PLACE_REPORT")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID anonymousId = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(anonymous).get("id").asText());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> files.requireOwned(anonymousId, owner, "PLACE_REPORT")).getStatusCode());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM stored_file WHERE id = :id AND owner_user_id IS NULL")
                .param("id", anonymousId).query(Integer.class).single());
    }
}
