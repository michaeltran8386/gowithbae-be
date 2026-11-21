package vn.gowithbae.platform;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Safe metadata and private content access for trusted callers; ownership/purpose mismatches are 404. */
@Service
public class FileService {
    public record FileMetadata(UUID id, UUID ownerUserId, String purpose, String contentType, long sizeBytes) {}

    private final JdbcClient jdbc;
    private vn.gowithbae.upload.FileStorage storage;

    public FileService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setStorage(vn.gowithbae.upload.FileStorage storage) {
        this.storage = storage;
    }

    /** Trusted callers must supply their authenticated user ID and close the returned stream. */
    public java.io.InputStream openOwned(UUID fileId, UUID userId, String purpose) {
        requireOwned(fileId, userId, purpose);
        String key = jdbc.sql("SELECT storage_key FROM stored_file WHERE id = :id AND owner_user_id = :user AND purpose = :purpose")
                .param("id", fileId).param("user", userId).param("purpose", purpose).query(String.class)
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp"));
        try {
            return storage.open(key);
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp");
        }
    }

    public FileMetadata requireOwned(UUID fileId, UUID userId, String purpose) {
        if (fileId == null || userId == null || purpose == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp");
        }
        return jdbc.sql("""
                SELECT id, owner_user_id, purpose, content_type, size_bytes FROM stored_file
                WHERE id = :id AND owner_user_id = :user AND purpose = :purpose
                """).param("id", fileId).param("user", userId).param("purpose", purpose)
                .query((rs, n) -> new FileMetadata(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                        rs.getString("purpose"), rs.getString("content_type"), rs.getLong("size_bytes")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp"));
    }
}
