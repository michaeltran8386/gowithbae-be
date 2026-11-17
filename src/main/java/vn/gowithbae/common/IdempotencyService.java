package vn.gowithbae.common;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

/**
 * Bảo đảm thao tác tạo/chốt mang header Idempotency-Key không tạo trùng khi bấm lặp hoặc retry (NFR-08).
 * Cần chạy bên trong transaction hiện hữu để dùng pg_advisory_xact_lock và rollback đồng bộ khi lỗi.
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public <T> ResponseEntity<T> execute(
            String actorKey,
            String operation,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            Supplier<ResponseEntity<T>> action) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        String key = idempotencyKey.trim();
        String requestHash = hashObject(requestPayload);

        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                .param("lockKey", actorKey + ":" + operation + ":" + key)
                .query(Object.class)
                .single();

        Optional<StoredResponse> existing = jdbc.sql("""
                SELECT request_hash, response_status, response_body::text AS body_text
                FROM idempotency_record
                WHERE actor_key = :actor AND operation = :op AND idem_key = :key AND expires_at > now()
                """)
                .param("actor", actorKey)
                .param("op", operation)
                .param("key", key)
                .query((rs, rowNum) -> new StoredResponse(
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getString("body_text")))
                .optional();

        if (existing.isPresent()) {
            StoredResponse cached = existing.get();
            if (!cached.requestHash().equals(requestHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency-Key đã được sử dụng với dữ liệu khác");
            }
            T body = objectMapper.readValue(cached.bodyText(), responseType);
            return ResponseEntity.status(cached.responseStatus()).body(body);
        }

        ResponseEntity<T> result = action.get();
        String bodyJson = objectMapper.writeValueAsString(result.getBody());
        jdbc.sql("""
                INSERT INTO idempotency_record(actor_key, operation, idem_key, request_hash, response_status, response_body)
                VALUES (:actor, :op, :key, :hash, :status, CAST(:body AS jsonb))
                """)
                .param("actor", actorKey)
                .param("op", operation)
                .param("key", key)
                .param("hash", requestHash)
                .param("status", result.getStatusCode().value())
                .param("body", bodyJson)
                .update();

        return result;
    }

    private String hashObject(Object payload) {
        return TokenHashUtil.sha256Hex(objectMapper.writeValueAsString(payload));
    }

    private record StoredResponse(String requestHash, int responseStatus, String bodyText) {}
}
