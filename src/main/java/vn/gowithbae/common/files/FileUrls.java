package vn.gowithbae.common.files;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * URL công khai của ảnh địa điểm / ảnh bìa bộ sưu tập, dựng từ id tệp.
 * Mặc định trỏ tới {@code GET /api/v1/files/{id}} (PublicFilesController) — đường dẫn tương đối, đi qua cùng
 * origin với web. Khi chuyển sang CDN/lưu trữ ngoài (OQ-21), đặt {@code app.files.public-base-url} trỏ tới nơi
 * phục vụ ảnh theo id.
 */
@Component
public class FileUrls {

    private final String baseUrl;

    public FileUrls(@Value("${app.files.public-base-url:}") String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "/api/v1/files/" : baseUrl.trim();
        this.baseUrl = base.endsWith("/") ? base : base + "/";
    }

    /** {@code null} khi không có tệp (FE hiển thị ảnh mặc định). */
    public URI publicUrl(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return null;
        }
        return URI.create(baseUrl + fileId);
    }
}
