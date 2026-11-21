package vn.gowithbae.upload;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lưu tệp trên Supabase Storage qua REST API, bucket riêng tư (OQ-21). Dùng khi máy chủ không có ổ đĩa lâu dài
 * (vd. Render): tệp không mất khi deploy/khởi động lại.
 *
 * <p>Cần khóa có quyền service (secret key {@code sb_secret_...} hoặc service_role JWT cũ) — chỉ đặt ở biến môi
 * trường server, không bao giờ đưa ra frontend. Bucket tạo sẵn, để private.
 */
@Component
@ConditionalOnProperty(name = "app.files.storage", havingValue = "supabase")
public class SupabaseFileStorage implements FileStorage {

    private final HttpClient http;
    private final String objectBase;
    private final String serviceKey;

    @Autowired
    public SupabaseFileStorage(
            @Value("${app.files.supabase.url}") String url,
            @Value("${app.files.supabase.service-key}") String serviceKey,
            @Value("${app.files.supabase.bucket:gowithbae-files}") String bucket) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), url, serviceKey, bucket);
    }

    SupabaseFileStorage(HttpClient http, String url, String serviceKey, String bucket) {
        if (url == null || url.isBlank() || serviceKey == null || serviceKey.isBlank() || bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.files.storage=supabase cần SUPABASE_URL, SUPABASE_SERVICE_KEY và bucket");
        }
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.http = http;
        this.objectBase = base + "/storage/v1/object/" + bucket + "/";
        this.serviceKey = serviceKey.trim();
    }

    @Override
    public void write(String key, byte[] bytes) throws IOException {
        FileStorage.requireValidKey(key);
        HttpResponse<String> res = send(request(key)
                .header("Content-Type", "application/octet-stream")
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("Supabase Storage upload failed: HTTP " + res.statusCode());
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        FileStorage.requireValidKey(key);
        HttpResponse<InputStream> res = send(request(key).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            // Supabase trả 400/404 khi không có object: coi như mất tệp.
            throw new IOException("Supabase Storage object not available: HTTP " + res.statusCode());
        }
        return res.body();
    }

    @Override
    public void delete(String key) throws IOException {
        FileStorage.requireValidKey(key);
        HttpResponse<String> res = send(request(key).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2 && res.statusCode() != 404 && res.statusCode() != 400) {
            throw new IOException("Supabase Storage delete failed: HTTP " + res.statusCode());
        }
    }

    private HttpRequest.Builder request(String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(objectBase + key))
                .timeout(Duration.ofSeconds(30))
                .header("apikey", serviceKey);
        // Khóa JWT kiểu cũ (service_role) gửi kèm Authorization; secret key mới (sb_secret_...) chỉ cần apikey.
        if (serviceKey.startsWith("eyJ")) {
            b.header("Authorization", "Bearer " + serviceKey);
        }
        return b;
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Supabase Storage", e);
        }
    }
}
