package vn.gowithbae.analytics;

import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vn.gowithbae.api.EventsApiDelegate;
import vn.gowithbae.api.model.*;
import vn.gowithbae.platform.CurrentUser;

@Service
public class EventsDelegate implements EventsApiDelegate {
    private static final Map<String, Set<String>> PROPERTIES = Map.of(
            "search_submitted", Set.of("search_id", "area_id", "category_id"),
            "results_shown", Set.of("search_id", "results_count", "area_id", "category_id"),
            "place_viewed", Set.of("area_id", "category_id"),
            "place_saved", Set.of("area_id", "category_id"),
            "comparison_opened", Set.of("area_id", "category_id"),
            "invite_created", Set.of("area_id", "category_id"),
            "vote_submitted", Set.of("candidate_id", "preference", "area_id", "category_id"),
            "date_plan_created", Set.of("area_id", "category_id"),
            "visit_self_reported", Set.of("answer", "area_id", "category_id"));
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final ObjectMapper json;
    public EventsDelegate(JdbcClient jdbc, CurrentUser current, ObjectMapper json) {
        this.jdbc = jdbc; this.current = current; this.json = json;
    }

    @Override
    @Transactional
    public ResponseEntity<Void> trackEvents(TrackEventsRequest request) {
        UUID user = current.optionalId().orElse(null);
        if (user != null) current.requireActive();
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty() || request.getEvents().size() > 50)
            throw bad("Batch must contain 1 to 50 events");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Map<String, String>> safe = new ArrayList<>();
        // Validate and sanitize the entire batch before the first INSERT, including duplicate IDs.
        for (AnalyticsEvent event : request.getEvents()) safe.add(validate(event, user, now));
        for (int i = 0; i < request.getEvents().size(); i++) {
            AnalyticsEvent e = request.getEvents().get(i);
            jdbc.sql("""
                    INSERT INTO analytics_event(event_id,name,occurred_at,session_id,platform,user_id,anonymous_id,object_id,props)
                    VALUES (:id,:name,:time,:session,:platform,:user,:anonymous,:object,CAST(:props AS jsonb))
                    ON CONFLICT (event_id) DO NOTHING
                    """).param("id", e.getEventId()).param("name", e.getName().getValue()).param("time", e.getOccurredAt())
                    .param("session", e.getSessionId()).param("platform", e.getPlatform().getValue()).param("user", user)
                    .param("anonymous", user == null ? e.getAnonymousId() : null).param("object", e.getObjectId())
                    .param("props", json.writeValueAsString(safe.get(i))).update();
        }
        return ResponseEntity.accepted().build();
    }

    private Map<String, String> validate(AnalyticsEvent e, UUID user, OffsetDateTime now) {
        if (e == null || e.getEventId() == null || e.getName() == null || e.getPlatform() == null || e.getOccurredAt() == null)
            throw bad("Event identity, name, platform and time are required");
        if (e.getOccurredAt().isAfter(now.plusMinutes(5)) || e.getOccurredAt().isBefore(now.minusDays(90)))
            throw bad("Event time must be within the last 90 days, with at most 5 minutes clock skew");
        identifier(e.getSessionId(), true);
        identifier(e.getAnonymousId(), user == null);
        if (e.getObjectId() != null) uuid(e.getObjectId());
        Set<String> allowed = PROPERTIES.get(e.getName().getValue());
        if (allowed == null) throw bad("Unsupported event");
        Map<String, String> props = new HashMap<>();
        if (e.getProperties() != null) for (var entry : e.getProperties().entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            String value = entry.getValue();
            if (value == null) throw bad("Null event property");
            switch (entry.getKey()) {
                case "area_id", "category_id", "search_id", "candidate_id" -> { uuid(value); value = UUID.fromString(value).toString(); }
                case "results_count" -> {
                    if (!value.matches("[0-9]{1,9}")) throw bad("Invalid results_count");
                    value = Integer.toString(Integer.parseInt(value));
                }
                case "preference" -> { if (!Set.of("LIKE", "SKIP").contains(value)) throw bad("Invalid preference"); }
                case "answer" -> { if (!Set.of("YES", "NO").contains(value)) throw bad("Invalid answer"); }
                default -> throw bad("Unsupported property");
            }
            props.put(entry.getKey(), value);
        }
        return props;
    }
    private static void identifier(String value, boolean required) {
        if (value == null && !required) return;
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw bad("Invalid session/anonymous identifier");
    }
    private static void uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) throw bad("Invalid UUID property");
        } catch (IllegalArgumentException ex) { throw bad("Invalid UUID property"); }
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
