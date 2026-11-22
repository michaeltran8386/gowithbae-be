package vn.gowithbae.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

/**
 * Gửi mã OTP qua API HTTP của Brevo (HTTPS, cổng 443) thay cho SMTP. Dùng khi máy chủ chặn cổng SMTP
 * (vd. gói Render miễn phí chặn 25/465/587). Bật bằng {@code app.mail.provider=brevo}.
 *
 * <p>Người gửi ({@code app.mail.from}) phải được xác minh trong Brevo (Senders, Domains &amp; Dedicated IPs → Senders).
 * Không log mã OTP hay địa chỉ người nhận.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
public class BrevoOtpDelivery implements OtpDelivery {

    private static final Logger log = LoggerFactory.getLogger(BrevoOtpDelivery.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String from;
    private final String fromName;

    @Autowired
    public BrevoOtpDelivery(ObjectMapper json,
            @Value("${app.mail.brevo.api-key:}") String apiKey,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:GoWithBae}") String fromName,
            @Value("${app.mail.brevo.url:https://api.brevo.com/v3/smtp/email}") String url) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), json, apiKey, from, fromName, url);
    }

    BrevoOtpDelivery(HttpClient http, ObjectMapper json, String apiKey, String from, String fromName, String url) {
        this.http = http;
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "GoWithBae" : fromName.trim();
        this.endpoint = URI.create(url);
        if (this.apiKey.isEmpty() || this.from.isEmpty()) {
            log.warn("app.mail.provider=brevo nhưng thiếu BREVO_API_KEY hoặc SMTP_FROM: không gửi được mã OTP");
        }
    }

    @Override
    public void send(String email, String code, String purpose) {
        if (apiKey.isEmpty() || from.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ email chưa sẵn sàng");
        }
        String body = json.writeValueAsString(Map.of(
                "sender", Map.of("email", from, "name", fromName),
                "to", List.of(Map.of("email", email)),
                "subject", "Mã xác minh GoWithBae",
                "textContent", "Mã xác minh của bạn: " + code + ". Không chia sẻ mã này với người khác."));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("api-key", apiKey)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                // Lỗi Brevo (sai key, người gửi chưa xác minh, hết hạn mức) không chứa mã OTP; cắt ngắn để log gọn.
                String detail = res.body() == null ? "" : res.body().substring(0, Math.min(300, res.body().length()));
                log.warn("Brevo từ chối gửi OTP: HTTP {} {}", res.statusCode(), detail);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa gửi được email. Hãy thử lại sau.");
            }
        } catch (IOException e) {
            log.warn("Không gọi được Brevo: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa gửi được email. Hãy thử lại sau.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa gửi được email. Hãy thử lại sau.");
        }
    }
}
