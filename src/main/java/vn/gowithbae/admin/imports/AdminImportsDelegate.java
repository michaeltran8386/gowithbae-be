package vn.gowithbae.admin.imports;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import vn.gowithbae.admin.AdminPermissions;
import vn.gowithbae.api.AdminImportsApiDelegate;
import vn.gowithbae.api.model.ImportBatch;

/** Nhập địa điểm bằng CSV (FR-30). shortcut: OQ-18 — quyền nhập tạm theo nhóm biên tập nội dung. */
@Service
public class AdminImportsDelegate implements AdminImportsApiDelegate {

    private final AdminImportService service;
    private final AdminPermissions permissions;

    public AdminImportsDelegate(AdminImportService service, AdminPermissions permissions) {
        this.service = service;
        this.permissions = permissions;
    }

    @Override
    public ResponseEntity<String> adminDownloadImportTemplate() {
        permissions.requireContentEdit();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"gowithbae-places-" + ImportRowValidator.TEMPLATE_VERSION + ".csv\"")
                .body(service.template());
    }

    @Override
    public ResponseEntity<ImportBatch> adminValidateImport(MultipartFile file) {
        permissions.requireContentEdit();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.validate(file));
    }

    @Override
    public ResponseEntity<ImportBatch> adminGetImport(UUID batchId) {
        permissions.requireContentRead();
        return ResponseEntity.ok(service.get(batchId));
    }

    @Override
    public ResponseEntity<ImportBatch> adminCommitImport(UUID batchId) {
        permissions.requireContentEdit();
        return ResponseEntity.ok(service.commit(batchId));
    }
}
