package vn.gowithbae.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Gửi OTP qua API Brevo (giả lập bằng HTTP server cục bộ). Không cần Docker hay tài khoản Brevo. */
class BrevoOtpDeliveryTest {

    final ObjectMapper json = JsonMapper.builder().build();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(201);
    HttpServer server;
    String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/smtp/email", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(ex.getRequestHeaders().getFirst("api-key"));
            byte[] res = (status.get() == 201 ? "{\"messageId\":\"<x@brevo>\"}" : "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), res.length);
            ex.getResponseBody().write(res);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BrevoOtpDelivery delivery(String key, String from) {
        return new BrevoOtpDelivery(HttpClient.newHttpClient(), json, key, from, "GoWithBae", url);
    }

    @Test
    void sendsCodeToRecipientFromVerifiedSenderWithApiKey() {
        delivery("xkeysib-test", "no-reply@gowithbae.test").send("ban@example.com", "123456", "LOGIN");

        assertThat(apiKeyHeader.get()).isEqualTo("xkeysib-test");
        JsonNode sent = json.readTree(body.get());
        assertThat(sent.get("sender").get("email").asText()).isEqualTo("no-reply@gowithbae.test");
        assertThat(sent.get("sender").get("name").asText()).isEqualTo("GoWithBae");
        assertThat(sent.get("to").get(0).get("email").asText()).isEqualTo("ban@example.com");
        assertThat(sent.get("subject").asText()).isEqualTo("Mã xác minh GoWithBae");
        assertThat(sent.get("textContent").asText()).contains("123456");
    }

    @Test
    void brevoRejectionBecomes503WithoutLeakingDetails() {
        status.set(401);
        assertThatThrownBy(() -> delivery("bad", "no-reply@gowithbae.test").send("ban@example.com", "123456", "LOGIN"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("Chưa gửi được email. Hãy thử lại sau.");
                });
    }

    @Test
    void missingKeyOrSenderMeansServiceNotReady() {
        assertThatThrownBy(() -> delivery("", "no-reply@gowithbae.test").send("ban@example.com", "1", "LOGIN"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("Dịch vụ email chưa sẵn sàng"));
        assertThatThrownBy(() -> delivery("xkeysib-test", " ").send("ban@example.com", "1", "LOGIN"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(body.get()).isNull(); // không gọi Brevo khi thiếu cấu hình
    }

    @Test
    void unreachableBrevoBecomes503() {
        server.stop(0);
        assertThatThrownBy(() -> delivery("xkeysib-test", "no-reply@gowithbae.test").send("ban@example.com", "1", "LOGIN"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
