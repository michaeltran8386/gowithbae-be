package vn.gowithbae.content;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.api.model.AdminSiteContent;
import vn.gowithbae.api.model.SiteContent;
import vn.gowithbae.api.model.SiteContentContact;
import vn.gowithbae.api.model.SiteContentFaqInner;
import vn.gowithbae.api.model.SiteContentFooter;
import vn.gowithbae.api.model.SiteContentHomeBanner;
import vn.gowithbae.api.model.SiteContentHomeHero;
import vn.gowithbae.api.model.SiteContentPrivacy;
import vn.gowithbae.api.model.SiteContentTerms;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.platform.AuditService;

/**
 * Nội dung trang tĩnh: mỗi mục (homeHero, homeBanner, faq, terms, privacy, contact, footer) là một dòng jsonb
 * trong {@code site_content}. Quản trị viên thay toàn bộ; audit chỉ ghi tên các mục đã đổi, không ghi nội dung.
 */
@Service
public class SiteContentService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public SiteContentService(JdbcClient jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    private record Row(String value, OffsetDateTime updatedAt, UUID updatedBy) {
    }

    private Map<String, Row> rows() {
        Map<String, Row> rows = new HashMap<>();
        jdbc.sql("SELECT key, value::text AS value, updated_at, updated_by FROM site_content")
                .query(rs -> {
                    rows.put(rs.getString("key"), new Row(rs.getString("value"),
                            rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", UUID.class)));
                });
        return rows;
    }

    @Transactional(readOnly = true)
    public SiteContent current() {
        Map<String, Row> r = rows();
        return new SiteContent(
                read(r, "homeHero", SiteContentHomeHero.class, new SiteContentHomeHero("", "")),
                read(r, "homeBanner", SiteContentHomeBanner.class, new SiteContentHomeBanner(false, "")),
                readFaq(r),
                read(r, "terms", SiteContentTerms.class, new SiteContentTerms("")),
                read(r, "privacy", SiteContentPrivacy.class, new SiteContentPrivacy("")),
                read(r, "contact", SiteContentContact.class, new SiteContentContact()),
                read(r, "footer", SiteContentFooter.class, new SiteContentFooter("")));
    }

    @Transactional(readOnly = true)
    public AdminSiteContent currentForAdmin() {
        Map<String, Row> r = rows();
        SiteContent c = current();
        Row latest = r.values().stream().filter(row -> row.updatedAt() != null)
                .max((a, b) -> a.updatedAt().compareTo(b.updatedAt())).orElse(null);
        return new AdminSiteContent(c.getHomeHero(), c.getHomeBanner(), c.getFaq(), c.getTerms(), c.getPrivacy(),
                c.getContact(), c.getFooter())
                .updatedAt(latest == null ? null : latest.updatedAt())
                .updatedBy(latest == null ? null : latest.updatedBy());
    }

    /** Thay toàn bộ; chỉ ghi các mục có thay đổi. Link banner phải là http(s) hoặc đường dẫn nội bộ bắt đầu bằng "/". */
    @Transactional
    public AdminSiteContent replace(SiteContent next, UUID actor) {
        String link = next.getHomeBanner().getLinkUrl();
        if (link != null && !link.isBlank() && !(link.startsWith("/") || link.startsWith("https://") || link.startsWith("http://"))) {
            throw Problems.badRequest("Link banner không hợp lệ",
                    List.of(Problems.fieldError("homeBanner.linkUrl", "Dùng http(s):// hoặc đường dẫn bắt đầu bằng /")));
        }
        if (next.getHomeBanner().getEnabled() && next.getHomeBanner().getText().isBlank()) {
            throw Problems.badRequest("Banner đang bật nhưng trống",
                    List.of(Problems.fieldError("homeBanner.text", "Nhập nội dung hoặc tắt banner")));
        }
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("homeHero", next.getHomeHero());
        sections.put("homeBanner", next.getHomeBanner());
        sections.put("faq", next.getFaq());
        sections.put("terms", next.getTerms());
        sections.put("privacy", next.getPrivacy());
        sections.put("contact", next.getContact() == null ? new SiteContentContact() : next.getContact());
        sections.put("footer", next.getFooter());

        Map<String, Row> before = rows();
        List<String> changed = new ArrayList<>();
        sections.forEach((key, value) -> {
            String text = json.writeValueAsString(value);
            Row old = before.get(key);
            if (old != null && Objects.equals(json.readTree(old.value()), json.readTree(text))) {
                return;
            }
            jdbc.sql("""
                    INSERT INTO site_content(key, value, updated_by, updated_at) VALUES (:key, CAST(:value AS jsonb), :actor, now())
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()
                    """).param("key", key).param("value", text).param("actor", actor).update();
            changed.add(key);
        });
        if (!changed.isEmpty()) {
            audit.log(actor, "SITE_CONTENT_UPDATED", "site_content", null, null, Map.of("sections", changed), null);
        }
        return currentForAdmin();
    }

    private <T> T read(Map<String, Row> rows, String key, Class<T> type, T fallback) {
        Row row = rows.get(key);
        return row == null ? fallback : json.readValue(row.value(), type);
    }

    private List<SiteContentFaqInner> readFaq(Map<String, Row> rows) {
        Row row = rows.get("faq");
        return row == null ? new ArrayList<>() : json.readValue(row.value(), new TypeReference<List<SiteContentFaqInner>>() {
        });
    }
}
