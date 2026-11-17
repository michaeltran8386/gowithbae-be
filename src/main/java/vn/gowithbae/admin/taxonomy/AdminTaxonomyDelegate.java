package vn.gowithbae.admin.taxonomy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.gowithbae.admin.AdminPermissions;
import vn.gowithbae.api.AdminTaxonomyApiDelegate;
import vn.gowithbae.api.model.ActiveStatus;
import vn.gowithbae.api.model.AdminUpdateTaxonomyRequest;
import vn.gowithbae.api.model.Area;
import vn.gowithbae.api.model.AreaWrite;
import vn.gowithbae.api.model.Taxonomy;
import vn.gowithbae.api.model.TaxonomyGroup;
import vn.gowithbae.api.model.TaxonomyWrite;
import vn.gowithbae.platform.AuditService;
import vn.gowithbae.platform.CurrentUser;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.place.SearchTextUpdater;

/**
 * Loại hình, nhãn và khu vực (FR-25). Không có xóa: chỉ đổi tên hoặc ngừng dùng để dữ liệu cũ còn nguyên (RULE-22).
 * Nhãn/khu vực ngừng dùng biến mất khỏi bộ lọc công khai nhưng vẫn gắn trên địa điểm cũ.
 */
@Service
public class AdminTaxonomyDelegate implements AdminTaxonomyApiDelegate {

    private final JdbcClient jdbc;
    private final SearchTextUpdater searchText;
    private final AuditService audit;

    private final CurrentUser currentUser;
    private final AdminPermissions permissions;

    public AdminTaxonomyDelegate(JdbcClient jdbc, SearchTextUpdater searchText, AuditService audit,
            CurrentUser currentUser, AdminPermissions permissions) {
        this.currentUser = currentUser;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.searchText = searchText;
        this.audit = audit;
    }

    /** Gồm cả nhãn ngừng dùng để quản trị thấy và khôi phục được. */
    @Override
    public ResponseEntity<List<Taxonomy>> adminListTaxonomies(TaxonomyGroup group) {
        permissions.requireContentRead();
        var rows = jdbc.sql("""
                SELECT id, group_code, code, name, status FROM taxonomy
                WHERE CAST(:group AS text) IS NULL OR group_code = CAST(:group AS text)
                ORDER BY group_code, status, code
                """)
                .param("group", group == null ? null : group.getValue())
                .query((rs, i) -> new Taxonomy(rs.getObject("id", UUID.class),
                        TaxonomyGroup.fromValue(rs.getString("group_code")), rs.getString("code"),
                        rs.getString("name"), ActiveStatus.fromValue(rs.getString("status"))))
                .list();
        return ResponseEntity.ok(rows);
    }

    @Override
    @Transactional
    public ResponseEntity<Taxonomy> adminCreateTaxonomy(TaxonomyWrite body) {
        permissions.requireTaxonomyAdmin();
        String name = requireName(body.getName());
        UUID id;
        try {
            id = jdbc.sql("INSERT INTO taxonomy (group_code, code, name) VALUES (:g, :c, :n) RETURNING id")
                    .param("g", body.getGroup().getValue()).param("c", body.getCode()).param("n", name)
                    .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw Problems.conflict("TAXONOMY_CODE_TAKEN", "Mã nhãn đã tồn tại trong nhóm này");
        }
        Taxonomy created = taxonomy(id);
        audit.log(currentUser.id(), "TAXONOMY_CREATE", "taxonomy", id.toString(), null, state(created.getName(), created.getStatus()), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** shortcut: OQ-18 chưa chốt gộp nhãn; chỉ đổi tên/ngừng dùng. Địa điểm đang gắn nhãn được tính lại search_text. */
    @Override
    @Transactional
    public ResponseEntity<Taxonomy> adminUpdateTaxonomy(UUID taxonomyId, AdminUpdateTaxonomyRequest body) {
        permissions.requireTaxonomyAdmin();
        Taxonomy before = taxonomy(taxonomyId);
        String name = body.getName() == null ? before.getName() : requireName(body.getName());
        ActiveStatus status = body.getStatus() == null ? before.getStatus() : body.getStatus();
        jdbc.sql("UPDATE taxonomy SET name = :n, status = :s WHERE id = :id")
                .param("n", name).param("s", status.getValue()).param("id", taxonomyId).update();
        searchText.refresh(jdbc.sql("SELECT place_id FROM place_tag WHERE taxonomy_id = :id")
                .param("id", taxonomyId).query(UUID.class).list());
        audit.log(currentUser.id(), "TAXONOMY_UPDATE", "taxonomy", taxonomyId.toString(),
                state(before.getName(), before.getStatus()), state(name, status), null);
        return ResponseEntity.ok(taxonomy(taxonomyId));
    }

    @Override
    @Transactional
    public ResponseEntity<Area> adminCreateArea(AreaWrite body) {
        permissions.requireTaxonomyAdmin();
        String name = requireName(body.getName());
        UUID id;
        try {
            id = jdbc.sql("INSERT INTO area (code, name) VALUES (:c, :n) RETURNING id")
                    .param("c", body.getCode()).param("n", name).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw Problems.conflict("AREA_CODE_TAKEN", "Mã khu vực đã tồn tại");
        }
        Area created = area(id);
        audit.log(currentUser.id(), "AREA_CREATE", "area", id.toString(), null, state(created.getName(), created.getStatus()), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    @Transactional
    public ResponseEntity<Area> adminUpdateArea(UUID areaId, AdminUpdateTaxonomyRequest body) {
        permissions.requireTaxonomyAdmin();
        Area before = area(areaId);
        String name = body.getName() == null ? before.getName() : requireName(body.getName());
        ActiveStatus status = body.getStatus() == null ? before.getStatus() : body.getStatus();
        jdbc.sql("UPDATE area SET name = :n, status = :s WHERE id = :id")
                .param("n", name).param("s", status.getValue()).param("id", areaId).update();
        if (!name.equals(before.getName())) {
            searchText.refresh(jdbc.sql("SELECT id FROM place WHERE area_id = :id")
                    .param("id", areaId).query(UUID.class).list());
        }
        audit.log(currentUser.id(), "AREA_UPDATE", "area", areaId.toString(),
                state(before.getName(), before.getStatus()), state(name, status), null);
        return ResponseEntity.ok(area(areaId));
    }

    private Taxonomy taxonomy(UUID id) {
        return jdbc.sql("SELECT id, group_code, code, name, status FROM taxonomy WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new Taxonomy(rs.getObject("id", UUID.class),
                        TaxonomyGroup.fromValue(rs.getString("group_code")), rs.getString("code"),
                        rs.getString("name"), ActiveStatus.fromValue(rs.getString("status"))))
                .optional()
                .orElseThrow(Problems::notFound);
    }

    private Area area(UUID id) {
        return jdbc.sql("SELECT id, code, name, status FROM area WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new Area(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), ActiveStatus.fromValue(rs.getString("status"))))
                .optional()
                .orElseThrow(Problems::notFound);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw Problems.badRequest("Tên không được để trống", List.of(Problems.fieldError("name", "Bắt buộc")));
        }
        return name.trim();
    }

    private static Map<String, Object> state(String name, ActiveStatus status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status.getValue());
        return m;
    }
}
