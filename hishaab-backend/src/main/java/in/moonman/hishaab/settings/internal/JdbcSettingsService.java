package in.moonman.hishaab.settings.internal;

import in.moonman.hishaab.settings.SettingsChanged;
import in.moonman.hishaab.settings.SettingsService;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings stored as JSONB rows. Reads go to the DB every time: they are rare, and this keeps every caller
 * consistent with the latest PATCH without cache invalidation.
 */
@Service
class JdbcSettingsService implements SettingsService {

    private static final Logger log = LoggerFactory.getLogger(JdbcSettingsService.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    JdbcSettingsService(JdbcTemplate jdbc, JsonMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    @Override
    public String scanCron() {
        return value(SCAN_CRON).stringValue();
    }

    @Override
    public ZoneId scanZone() {
        return ZoneId.of(value(SCAN_ZONE).stringValue());
    }

    @Override
    public int scanServerDelayMinutes() {
        return value(SCAN_SERVER_DELAY_MINUTES).intValue();
    }

    @Override
    public List<String> senderAllowlist() {
        List<String> parts = new ArrayList<>();
        for (JsonNode entry : value(SENDERS_ALLOWLIST)) {
            parts.add(entry.stringValue());
        }
        return Collections.unmodifiableList(parts);
    }

    @Override
    public int matchSmsEmailWindowMinutes() {
        return value(MATCH_SMS_EMAIL_WINDOW_MINUTES).intValue();
    }

    @Override
    public double aiCategoryConfidenceMin() {
        return value(AI_CATEGORY_CONFIDENCE_MIN).doubleValue();
    }

    @Override
    public int backfillDays() {
        return value(BACKFILL_DAYS).intValue();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, JsonNode> all() {
        Map<String, JsonNode> stored = new HashMap<>();
        jdbc.query("SELECT key, value::text FROM settings",
                rs -> {
                    stored.put(rs.getString(1), json.readTree(rs.getString(2)));
                });
        Map<String, JsonNode> ordered = new LinkedHashMap<>();
        for (String key : SettingDefinitions.keys()) {
            JsonNode node = stored.get(key);
            if (node == null) {
                throw new IllegalStateException("setting '" + key + "' missing from DB; migrations incomplete?");
            }
            ordered.put(key, node);
        }
        return ordered;
    }

    @Override
    @Transactional
    public Map<String, JsonNode> update(Map<String, JsonNode> changes) {
        // Validate everything before touching the DB, so one bad key leaves all values unchanged.
        Map<String, JsonNode> normalised = new LinkedHashMap<>();
        changes.forEach((key, raw) -> normalised.put(key, SettingDefinitions.normalise(key, raw)));

        normalised.forEach((key, value) -> {
            String text = json.writeValueAsString(value);
            int changed = jdbc.update("""
                    UPDATE settings SET value = ?::jsonb, updated_at = now()
                    WHERE key = ? AND value IS DISTINCT FROM ?::jsonb""", text, key, text);
            if (changed > 0) {
                log.info("event=setting_changed key={}", key);
                events.publishEvent(new SettingsChanged(key));
            }
        });
        return all();
    }

    private JsonNode value(String key) {
        List<String> rows = jdbc.queryForList("SELECT value::text FROM settings WHERE key = ?", String.class, key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("setting '" + key + "' missing from DB; migrations incomplete?");
        }
        return json.readTree(rows.getFirst());
    }
}
