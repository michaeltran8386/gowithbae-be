package vn.gowithbae.admin.imports;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.admin.place.AdminPlaceService;
import vn.gowithbae.api.model.ImportBatch;
import vn.gowithbae.api.model.ImportBatch.StatusEnum;
import vn.gowithbae.api.model.ImportBatchRowsInner;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.common.web.Problems;

/**
 * Nhập địa điểm bằng CSV (FR-30, RULE-23): tải lên → kiểm tra thử (chưa ghi địa điểm) → commit thành nháp.
 * shortcut: OQ-19 — chỉ tạo mới, commit khi toàn lô hợp lệ; OQ-21 — giới hạn 2 MB / 1000 dòng là đề xuất.
 * Mẫu v1 chưa có giờ mở cửa và ảnh: biên tập bổ sung trên màn sửa nháp trước khi gửi duyệt.
 */
@Service
public class AdminImportService {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final int MAX_ROWS = 1000;

    private static final TypeReference<Map<String, String>> RAW_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ImportRowValidator validator;
    private final AdminPlaceService places;
    private final AuditService audit;
    private final ObjectMapper json;
    private final CurrentUser currentUser;

    public AdminImportService(JdbcClient jdbc, ImportRowValidator validator, AdminPlaceService places,
            AuditService audit, ObjectMapper json, CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.validator = validator;
        this.places = places;
        this.audit = audit;
        this.json = json;
    }

    /** File mẫu: chỉ dòng tiêu đề (không kèm dòng ví dụ để tránh nhập nhầm dữ liệu giả). */
    public String template() {
        return String.join(",", ImportRowValidator.COLUMNS) + "\r\n";
    }

    /** Kiểm tra thử và lưu kết quả từng dòng; lỗi cấp file (mã hóa, cột, kích thước) trả 400 và không tạo lô. */
    @Transactional
    public ImportBatch validate(MultipartFile file) {
        List<Map<String, String>> rows = parse(file);
        ImportRowValidator.Lookup lookup = validator.lookup();
        Map<String, Integer> firstLineByCode = new HashMap<>();
        List<ImportRowValidator.RowResult> results = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> raw = rows.get(i);
            int line = i + 2; // dòng 1 là tiêu đề
            String code = raw.get("code");
            Integer dupLine = code.isEmpty() ? null : firstLineByCode.putIfAbsent(code, line);
            results.add(validator.validate(raw, lookup, dupLine));
        }
        int errorRows = (int) results.stream().filter(r -> !r.valid()).count();

        UUID batchId = jdbc.sql("""
                INSERT INTO import_batch (created_by, template_version, status, total_rows, error_rows)
                VALUES (:actor, :version, :status, :total, :errors) RETURNING id
                """)
                .param("actor", currentUser.id())
                .param("version", ImportRowValidator.TEMPLATE_VERSION)
                .param("status", errorRows == 0 ? "VALIDATED" : "INVALID")
                .param("total", rows.size())
                .param("errors", errorRows)
                .query(UUID.class)
                .single();
        for (int i = 0; i < rows.size(); i++) {
            String code = rows.get(i).get("code");
            jdbc.sql("""
                    INSERT INTO import_row_result (batch_id, row_number, place_code, valid, errors, raw)
                    VALUES (:batch, :row, :code, :valid, CAST(:errors AS jsonb), CAST(:raw AS jsonb))
                    """)
                    .param("batch", batchId)
                    .param("row", i + 2)
                    .param("code", code.isEmpty() ? null : code)
                    .param("valid", results.get(i).valid())
                    .param("errors", json.writeValueAsString(results.get(i).errors()))
                    .param("raw", json.writeValueAsString(rows.get(i)))
                    .update();
        }
        return get(batchId);
    }

    @Transactional(readOnly = true)
    public ImportBatch get(UUID batchId) {
        ImportBatch batch = jdbc.sql("""
                SELECT id, status, template_version, total_rows, error_rows, created_at, committed_at
                FROM import_batch WHERE id = :id
                """)
                .param("id", batchId)
                .query((rs, i) -> new ImportBatch(rs.getObject("id", UUID.class),
                        StatusEnum.fromValue(rs.getString("status")), rs.getInt("total_rows"), rs.getInt("error_rows"),
                        rs.getObject("created_at", OffsetDateTime.class))
                        .templateVersion(rs.getString("template_version"))
                        .committedAt(rs.getObject("committed_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(Problems::notFound);
        jdbc.sql("SELECT row_number, place_code, valid, errors FROM import_row_result WHERE batch_id = :id ORDER BY row_number")
                .param("id", batchId)
                .query(rs -> {
                    batch.addRowsItem(new ImportBatchRowsInner(rs.getInt("row_number"), rs.getBoolean("valid"))
                            .placeCode(rs.getString("place_code"))
                            .errors(json.readValue(rs.getString("errors"), new TypeReference<List<String>>() {
                            })));
                });
        return batch;
    }

    /**
     * Ghi toàn lô thành địa điểm nháp trong một transaction. Kiểm tra lại từng dòng vì danh mục/mã có thể
     * đã đổi sau lúc kiểm tra thử; còn lỗi thì không ghi gì và yêu cầu tải lại.
     */
    @Transactional
    public ImportBatch commit(UUID batchId) {
        String status = jdbc.sql("SELECT status FROM import_batch WHERE id = :id FOR UPDATE")
                .param("id", batchId).query(String.class).optional().orElseThrow(Problems::notFound);
        if ("COMMITTED".equals(status)) {
            throw Problems.conflict("IMPORT_ALREADY_COMMITTED", "Lô này đã được ghi");
        }
        if (!"VALIDATED".equals(status)) {
            throw Problems.conflict("RULE-23", "Lô còn dòng lỗi; sửa file và tải lại");
        }
        List<Map<String, String>> rows = jdbc.sql("SELECT raw FROM import_row_result WHERE batch_id = :id ORDER BY row_number")
                .param("id", batchId)
                .query((rs, i) -> json.readValue(rs.getString("raw"), RAW_TYPE))
                .list();
        ImportRowValidator.Lookup lookup = validator.lookup();
        String reason = "Nhập CSV lô " + batchId;
        for (int i = 0; i < rows.size(); i++) {
            var result = validator.validate(rows.get(i), lookup, null);
            if (!result.valid()) {
                throw Problems.conflict("IMPORT_STALE",
                        "Dòng " + (i + 2) + " không còn hợp lệ (" + String.join("; ", result.errors()) + "); tải lại file");
            }
            places.createDraft(result.write(), reason);
        }
        jdbc.sql("UPDATE import_batch SET status = 'COMMITTED', committed_at = now() WHERE id = :id")
                .param("id", batchId).update();
        audit.log(currentUser.id(), "IMPORT_COMMIT", "import_batch", batchId.toString(), null, Map.of("places", rows.size()), null);
        return get(batchId);
    }

    /** Đọc file: UTF-8 (cho phép BOM), đủ cột mẫu v1, không quá giới hạn. Mọi giá trị được trim. */
    private List<Map<String, String>> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw Problems.badRequest("File rỗng", List.of(Problems.fieldError("file", "Chưa chọn file hoặc file rỗng")));
        }
        if (file.getSize() > MAX_BYTES) {
            throw Problems.badRequest("File quá lớn", List.of(Problems.fieldError("file", "Tối đa 2 MB")));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw Problems.badRequest("File phải mã hóa UTF-8", List.of(Problems.fieldError("file", "Lưu lại file dạng CSV UTF-8")));
        } catch (IOException e) {
            throw Problems.badRequest("Không đọc được file", List.of(Problems.fieldError("file", "Không đọc được file")));
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<String> header = parser.getHeaderNames();
            List<String> missing = ImportRowValidator.COLUMNS.stream().filter(c -> !header.contains(c)).toList();
            List<String> unknown = header.stream().filter(c -> !ImportRowValidator.COLUMNS.contains(c)).toList();
            if (!missing.isEmpty() || !unknown.isEmpty()) {
                List<Map<String, String>> errors = new ArrayList<>();
                missing.forEach(c -> errors.add(Problems.fieldError("file", "Thiếu cột " + c)));
                unknown.forEach(c -> errors.add(Problems.fieldError("file", "Cột không thuộc mẫu " + ImportRowValidator.TEMPLATE_VERSION + ": " + c)));
                throw Problems.badRequest("Cột không khớp mẫu " + ImportRowValidator.TEMPLATE_VERSION, errors);
            }
            for (CSVRecord record : parser) {
                if (rows.size() >= MAX_ROWS) {
                    throw Problems.badRequest("File quá nhiều dòng", List.of(Problems.fieldError("file", "Tối đa " + MAX_ROWS + " dòng dữ liệu")));
                }
                Map<String, String> raw = new LinkedHashMap<>();
                for (String column : ImportRowValidator.COLUMNS) {
                    String value = record.isSet(column) ? record.get(column) : "";
                    raw.put(column, value == null ? "" : value.trim());
                }
                rows.add(raw);
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            throw Problems.badRequest("File CSV sai định dạng", List.of(Problems.fieldError("file", "Kiểm tra dấu ngoặc kép và dấu phẩy")));
        }
        if (rows.isEmpty()) {
            throw Problems.badRequest("File không có dòng dữ liệu", List.of(Problems.fieldError("file", "Cần ít nhất một dòng sau tiêu đề")));
        }
        return rows;
    }
}
