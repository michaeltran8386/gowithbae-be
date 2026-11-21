package vn.gowithbae.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** SupabaseFileStorage gọi đúng REST API của Supabase Storage (giả lập bằng HTTP server cục bộ). Không cần Docker. */
class SupabaseFileStorageTest {

    record Call(String method, String path, String apikey, String auth) {}

    HttpServer server;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/object/", ex -> {
            String path = ex.getRequestURI().getPath();
            calls.add(new Call(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("apikey"),
                    ex.getRequestHeaders().getFirst("Authorization")));
            String key = path.substring("/storage/v1/object/".length());
            int status;
            byte[] body = new byte[0];
            switch (ex.getRequestMethod()) {
                case "POST" -> {
                    status = objects.putIfAbsent(key, ex.getRequestBody().readAllBytes()) == null ? 200 : 409;
                }
                case "GET" -> {
                    byte[] b = objects.get(key);
                    status = b == null ? 400 : 200;   // Supabase trả 400 "Object not found"
                    if (b != null) body = b;
                }
                case "DELETE" -> status = objects.remove(key) == null ? 400 : 200;
                default -> status = 405;
            }
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void writesReadsAndDeletesInThePrivateBucketWithTheSecretKey() throws IOException {
        var storage = new SupabaseFileStorage(HttpClient.newHttpClient(), baseUrl + "/", "sb_secret_test", "gowithbae-files");
        String key = UUID.randomUUID().toString();

        storage.write(key, new byte[] { 1, 2, 3 });
        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }
        storage.delete(key);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
        storage.delete(key); // xóa lần hai không lỗi

        assertThat(calls).first().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.path()).isEqualTo("/storage/v1/object/gowithbae-files/" + key);
            assertThat(c.apikey()).isEqualTo("sb_secret_test");
            assertThat(c.auth()).isNull(); // secret key mới không gửi qua Authorization
        });
    }

    @Test
    void legacyServiceRoleJwtIsAlsoSentAsBearer() throws IOException {
        var storage = new SupabaseFileStorage(HttpClient.newHttpClient(), baseUrl, "eyJhbGciOi.test", "b");
        storage.write(UUID.randomUUID().toString(), new byte[] { 9 });
        assertThat(calls.getFirst().auth()).isEqualTo("Bearer eyJhbGciOi.test");
    }

    @Test
    void rejectsKeysThatAreNotUuidsAndDuplicateUploads() throws IOException {
        var storage = new SupabaseFileStorage(HttpClient.newHttpClient(), baseUrl, "sb_secret_test", "b");
        assertThatThrownBy(() -> storage.open("../etc/passwd")).isInstanceOf(IOException.class);
        assertThat(calls).isEmpty();

        String key = UUID.randomUUID().toString();
        storage.write(key, new byte[] { 1 });
        assertThatThrownBy(() -> storage.write(key, new byte[] { 2 })).isInstanceOf(IOException.class);
    }

    @Test
    void missingConfigurationFailsFast() {
        assertThatThrownBy(() -> new SupabaseFileStorage(HttpClient.newHttpClient(), "", "k", "b")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SupabaseFileStorage(HttpClient.newHttpClient(), baseUrl, " ", "b")).isInstanceOf(IllegalStateException.class);
    }
}
