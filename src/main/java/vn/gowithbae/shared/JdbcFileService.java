package vn.gowithbae.shared;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.StoredFile;

/** Cài đặt kiểm tra và đọc metadata tệp trên bảng stored_file. */
@Service
public class JdbcFileService implements FileService {

    private final JdbcClient jdbc;
    private final String publicBaseUrl;

    public JdbcFileService(JdbcClient jdbc, @Value("${app.storage.public-base-url:}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    @Override
    public void validateAttachments(
            @Nullable UUID actorUserId,
            @Nullable List<UUID> fileIds,
            String expectedPurpose,
            boolean requireOwnedByUser) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        Set<UUID> distinctIds = fileIds.stream().collect(Collectors.toSet());
        List<FileCheckRow> rows = jdbc.sql("""
                SELECT id, owner_user_id, purpose
                FROM stored_file
                WHERE id IN (:ids)
                """)
                .param("ids", distinctIds)
                .query((rs, rowNum) -> new FileCheckRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("purpose")))
                .list();

        if (rows.size() != distinctIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp đính kèm không tồn tại");
        }
        for (FileCheckRow row : rows) {
            if (!expectedPurpose.equals(row.purpose())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại tệp đính kèm không phù hợp");
            }
            if (requireOwnedByUser) {
                if (actorUserId == null || !actorUserId.equals(row.ownerUserId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có quyền dùng tệp đính kèm này");
                }
            } else if (row.ownerUserId() != null && !row.ownerUserId().equals(actorUserId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có quyền dùng tệp đính kèm này");
            }
        }
    }

    @Override
    public Map<UUID, StoredFile> getFiles(Collection<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<UUID> distinctIds = Set.copyOf(fileIds);
        List<StoredFile> files = jdbc.sql("""
                SELECT id, content_type, size_bytes, storage_key
                FROM stored_file
                WHERE id IN (:ids)
                """)
                .param("ids", distinctIds)
                .query((rs, rowNum) -> new StoredFile(
                        rs.getObject("id", UUID.class),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"))
                        .url(resolveUri(rs.getString("storage_key"))))
                .list();

        Map<UUID, StoredFile> map = new LinkedHashMap<>();
        for (StoredFile file : files) {
            map.put(file.getId(), file);
        }
        return map;
    }

    public @Nullable URI resolveUri(@Nullable String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        String trimmed = storageKey.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return URI.create(trimmed);
        }
        if (!publicBaseUrl.isEmpty()) {
            String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
            String key = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
            return URI.create(base + key);
        }
        return null;
    }

    private record FileCheckRow(UUID id, @Nullable UUID ownerUserId, String purpose) {}
}
