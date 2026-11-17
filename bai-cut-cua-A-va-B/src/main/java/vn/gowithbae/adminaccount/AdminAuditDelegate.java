package vn.gowithbae.adminaccount;

import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.api.AdminAuditApiDelegate;
import vn.gowithbae.api.model.*;
import vn.gowithbae.platform.CurrentUser;

@Service
public class AdminAuditDelegate implements AdminAuditApiDelegate {
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final ObjectMapper json;
    public AdminAuditDelegate(JdbcClient jdbc, CurrentUser current, ObjectMapper json) {
        this.jdbc = jdbc; this.current = current; this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<AuditLogPage> adminListAuditLogs(String objectType, String objectId, UUID actorId,
            LocalDate from, LocalDate to, Integer page, Integer size) {
        current.requireActive(); current.requireRole("SUPER_ADMIN");
        int p = page == null ? 0 : page, s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > 100 || (from != null && to != null && from.isAfter(to)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination/date range");
        String where = " WHERE true";
        Map<String, Object> params = new HashMap<>();
        if (objectType != null) { where += " AND object_type=:type"; params.put("type", objectType); }
        if (objectId != null) { where += " AND object_id=:object"; params.put("object", objectId); }
        if (actorId != null) { where += " AND actor_id=:actor"; params.put("actor", actorId); }
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        if (from != null) { where += " AND created_at>=:from"; params.put("from", from.atStartOfDay(zone).toOffsetDateTime()); }
        if (to != null) { where += " AND created_at<:to"; params.put("to", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()); }
        long total = jdbc.sql("SELECT count(*) FROM audit_log" + where).params(params).query(Long.class).single();
        var items = jdbc.sql("SELECT * FROM audit_log" + where + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
                .params(params).param("limit", s).param("offset", (long) p * s).query((rs, n) ->
                    new AuditLog(rs.getLong("id"), rs.getString("action"), rs.getString("object_type"),
                            rs.getObject("created_at", OffsetDateTime.class))
                        .actorId(rs.getObject("actor_id", UUID.class)).objectId(rs.getString("object_id"))
                        .before(read(rs.getString("before_value"))).after(read(rs.getString("after_value")))
                        .reason(rs.getString("reason")).traceId(rs.getString("trace_id"))).list();
        return ResponseEntity.ok(new AuditLogPage().items(items).page(new PageMeta(p, s, total, (int) ((total+s-1)/s))));
    }
    private Object read(String value) { return value == null ? null : json.readValue(value, Object.class); }
}
