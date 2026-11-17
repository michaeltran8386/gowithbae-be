package vn.gowithbae.shared;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.StoredFile;
import vn.gowithbae.platform.FileService;

/**
 * Điều phối kiểm tra và đọc metadata tệp dựa trên vn.gowithbae.platform.FileService của Người A.
 * Hỗ trợ cả tệp thuộc tài khoản (SUPPORT, PLACE_REPORT của member) lẫn tệp ẩn danh (PLACE_REPORT owner_user_id IS NULL).
 */
@Service
public class StoredFileLookup {

    private final JdbcClient jdbc;
    private final FileService fileService;

    public StoredFileLookup(JdbcClient jdbc, FileService fileService) {
        this.jdbc = jdbc;
        this.fileService = fileService;
    }

    public void validateSupportFiles(UUID ownerUserId, @Nullable List<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        for (UUID fileId : fileIds) {
            fileService.requireOwned(fileId, ownerUserId, "SUPPORT");
        }
    }

    public void validatePlaceReportFile(@Nullable UUID senderUserId, @Nullable UUID fileId) {
        if (fileId == null) {
            return;
        }
        if (senderUserId != null) {
            // Thành viên đăng nhập chỉ được gắn tệp do chính mình tải lên hoặc tệp báo sai ẩn danh vừa tải trước đó nếu khớp, nhưng chuẩn nhất là kiểm tra sở hữu hoặc anonymous cùng luồng.
            OptionalOwnerRow row = findPlaceReportRow(fileId);
            if (row == null || !"PLACE_REPORT".equals(row.purpose())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp đính kèm không hợp lệ");
            }
            if (row.ownerUserId() != null) {
                fileService.requireOwned(fileId, senderUserId, "PLACE_REPORT");
            }
            return;
        }
        OptionalOwnerRow row = findPlaceReportRow(fileId);
        if (row == null || !"PLACE_REPORT".equals(row.purpose()) || row.ownerUserId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp đính kèm không tồn tại hoặc không hợp lệ");
        }
    }

    public Map<UUID, StoredFile> getMetadata(Collection<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<UUID> distinctIds = Set.copyOf(fileIds);
        List<StoredFile> files = jdbc.sql("""
                SELECT id, content_type, size_bytes
                FROM stored_file
                WHERE id IN (:ids)
                """)
                .param("ids", distinctIds)
                .query((rs, rowNum) -> new StoredFile(
                        rs.getObject("id", UUID.class),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"))
                        .url(null))
                .list();

        Map<UUID, StoredFile> map = new LinkedHashMap<>();
        for (StoredFile file : files) {
            map.put(file.getId(), file);
        }
        return map;
    }

    private @Nullable OptionalOwnerRow findPlaceReportRow(UUID fileId) {
        return jdbc.sql("""
                SELECT owner_user_id, purpose
                FROM stored_file
                WHERE id = :id
                """)
                .param("id", fileId)
                .query((rs, rowNum) -> new OptionalOwnerRow(
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("purpose")))
                .optional()
                .orElse(null);
    }

    private record OptionalOwnerRow(@Nullable UUID ownerUserId, String purpose) {}
}
