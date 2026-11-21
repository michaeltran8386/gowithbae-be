package vn.gowithbae.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * Nơi lưu nội dung tệp (ảnh, đính kèm). Metadata và quyền truy cập nằm ở bảng {@code stored_file};
 * khóa lưu trữ là UUID dạng chuỗi ({@code stored_file.storage_key}). Chọn bằng {@code app.files.storage}:
 * {@code local} (mặc định, thư mục trên đĩa) hoặc {@code supabase} (Supabase Storage, bucket riêng tư).
 * Backend luôn tự phục vụ tệp qua {@code /api/v1/files/{id}} nên quy tắc ai xem được tệp nào không đổi.
 */
public interface FileStorage {

    void write(String key, byte[] bytes) throws IOException;

    /** Mở luồng đọc; ném {@link IOException} khi không có tệp. Người gọi phải đóng luồng. */
    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;

    /** Khóa lưu trữ hợp lệ là UUID chuẩn; chặn đường dẫn lạ (../, ký tự đặc biệt). */
    static void requireValidKey(String key) throws IOException {
        try {
            if (key == null || !java.util.UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid storage key");
        }
    }
}
