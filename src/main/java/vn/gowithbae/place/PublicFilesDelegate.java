package vn.gowithbae.place;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.FilesApiDelegate;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.upload.LocalFileStorage;

/**
 * Ảnh công khai: chỉ tệp PLACE_IMAGE đã gắn vào một địa điểm, hoặc tệp COLLECTION đang là ảnh bìa bộ sưu tập.
 * Ảnh vừa tải lên chưa gắn, ảnh đại diện, tệp báo sai/hỗ trợ đều 404 (không lộ sự tồn tại).
 * Gồm cả ảnh của địa điểm chưa công khai để trang quản trị xem được trong thẻ img (không gửi được bearer);
 * id tệp là UUID ngẫu nhiên. shortcut: OQ-21 — đọc từ kho local; production chuyển sang CDN qua app.files.public-base-url.
 */
@Service
public class PublicFilesDelegate implements FilesApiDelegate {

    private final JdbcClient jdbc;
    private final LocalFileStorage storage;

    public PublicFilesDelegate(JdbcClient jdbc, LocalFileStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    private record PublicFile(String storageKey, String contentType) {
    }

    @Override
    public ResponseEntity<Resource> getPublicFile(UUID fileId) {
        PublicFile file = jdbc.sql("""
                SELECT f.storage_key, f.content_type FROM stored_file f
                WHERE f.id = :id AND (
                    (f.purpose = 'PLACE_IMAGE' AND EXISTS (SELECT 1 FROM place_image i WHERE i.file_id = f.id))
                    OR (f.purpose = 'COLLECTION' AND EXISTS (SELECT 1 FROM collection c WHERE c.cover_file_id = f.id)))
                """)
                .param("id", fileId)
                .query((rs, i) -> new PublicFile(rs.getString("storage_key"), rs.getString("content_type")))
                .optional()
                .orElseThrow(Problems::notFound);
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new InputStreamResource(storage.open(file.storageKey())));
        } catch (IOException e) {
            // Có metadata nhưng mất tệp trên đĩa (vd. dữ liệu demo không có tệp thật).
            throw Problems.notFound();
        }
    }
}
