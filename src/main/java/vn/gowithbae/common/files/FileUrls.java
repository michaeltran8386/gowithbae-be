package vn.gowithbae.common.files;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Đổi {@code stored_file.storage_key} thành URL công khai.
 * shortcut: OQ-21/OQ-22 chưa chọn nơi lưu tệp; tạm ghép {@code app.files.public-base-url} + key.
 * Thống nhất lại với phần Uploads khi chọn dịch vụ lưu trữ.
 */
@Component
public class FileUrls {

    private final String baseUrl;

    public FileUrls(@Value("${app.files.public-base-url:}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") || baseUrl.isEmpty() ? baseUrl : baseUrl + "/";
    }

    /** {@code null} khi không có tệp hoặc chưa cấu hình nơi lưu (FE hiển thị ảnh mặc định). */
    public URI publicUrl(String storageKey) {
        if (storageKey == null || baseUrl.isEmpty()) {
            return null;
        }
        return URI.create(baseUrl + storageKey);
    }
}
