package vn.gowithbae.upload;

import java.io.IOException;
import java.sql.Types;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.gowithbae.api.UploadsApiDelegate;
import vn.gowithbae.api.model.StoredFile;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.platform.RequestRateLimiter;

/** OQ-21 decision: images only, private local storage, no URL until a download policy is agreed. */
@Service
public class UploadsDelegate implements UploadsApiDelegate {
    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final RequestRateLimiter limiter;
    private final NativeWebRequest request;
    private final FileStorage storage;
    private final TransactionTemplate transaction;
    private final int maxBytes;
    private final int perMinute;

    public UploadsDelegate(JdbcClient jdbc, CurrentUser user, RequestRateLimiter limiter,
            NativeWebRequest request, FileStorage storage, PlatformTransactionManager manager,
            @Value("${app.files.max-bytes:5242880}") int maxBytes,
            @Value("${app.files.upload-limit-per-minute:20}") int perMinute) {
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE || perMinute < 1) throw new IllegalArgumentException("Invalid upload limits");
        this.jdbc = jdbc;
        this.user = user;
        this.limiter = limiter;
        this.request = request;
        this.storage = storage;
        this.maxBytes = maxBytes;
        this.perMinute = perMinute;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ResponseEntity<StoredFile> uploadFile(MultipartFile file, String purpose) {
        if (purpose == null || !Set.of("AVATAR", "PLACE_IMAGE", "PLACE_REPORT", "SUPPORT", "COLLECTION").contains(purpose)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload purpose");
        }
        UUID owner = user.optionalId().orElse(null);
        if (owner == null && !purpose.equals("PLACE_REPORT")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign-in required for this purpose");
        }
        if (owner != null) user.requireActive();
        if (purpose.equals("PLACE_IMAGE") || purpose.equals("COLLECTION")) {
            user.requireAnyRole("EDITOR", "REVIEWER", "SUPER_ADMIN");
        }
        HttpServletRequest peer = request.getNativeRequest(HttpServletRequest.class);
        if (peer == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Request unavailable");
        if (!limiter.consume("uploads-peer", peer.getRemoteAddr(), perMinute, Duration.ofMinutes(1))
                || (owner != null && !limiter.consume("uploads-user", owner.toString(), perMinute, Duration.ofMinutes(1)))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Upload limit reached");
        }
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        if (file.getSize() > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large");
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(maxBytes + 1);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot read upload");
        }
        if (bytes.length > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large");
        String mime = ImageValidator.validate(bytes, file.getContentType());
        UUID id = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        try {
            storage.write(key, bytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store upload");
        }
        try {
            transaction.executeWithoutResult(status -> jdbc.sql("""
                    INSERT INTO stored_file (id, owner_user_id, purpose, content_type, size_bytes, storage_key)
                    VALUES (:id, :owner, :purpose, :mime, :size, :key)
                    """).param("id", id).param("owner", owner, Types.OTHER).param("purpose", purpose)
                    .param("mime", mime).param("size", bytes.length).param("key", key).update());
        } catch (RuntimeException ex) {
            try { storage.delete(key); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot save upload metadata");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new StoredFile(id, mime, (long) bytes.length).url(null));
    }
}
