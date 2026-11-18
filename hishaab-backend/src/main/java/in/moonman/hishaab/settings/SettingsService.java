package in.moonman.hishaab.settings;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Public API of the settings module (SPEC-001). Values live in the {@code settings} table and can change at
 * runtime; every successful change publishes {@link SettingsChanged} per changed key.
 */
public interface SettingsService {

    String SCAN_CRON = "scan.cron";
    String SCAN_ZONE = "scan.zone";
    String SCAN_SERVER_DELAY_MINUTES = "scan.serverDelayMinutes";
    String SENDERS_ALLOWLIST = "senders.allowlist";
    String MATCH_SMS_EMAIL_WINDOW_MINUTES = "match.smsEmailWindowMinutes";
    String AI_CATEGORY_CONFIDENCE_MIN = "ai.categoryConfidenceMin";
    String BACKFILL_DAYS = "backfill.days";

    /** Spring 6-field cron for the daily scan. */
    String scanCron();

    ZoneId scanZone();

    int scanServerDelayMinutes();

    /** Allowlist entry meaning "accept every sender" (R-001-3); filtering is then up to the sync worker. */
    String ALLOW_ALL_SENDERS = "*";

    /** Stable middle parts of sender IDs, uppercase (e.g. {@code AXISBK}), or {@link #ALLOW_ALL_SENDERS}. */
    List<String> senderAllowlist();

    int matchSmsEmailWindowMinutes();

    /** Minimum AI confidence (0..1) to accept a category without review. Not money, so a double is fine. */
    double aiCategoryConfidenceMin();

    int backfillDays();

    /** All settings, keyed by setting name, in a stable order. */
    Map<String, JsonNode> all();

    /**
     * Validates every entry first; if any is invalid nothing is written and an
     * {@link in.moonman.hishaab.shared.ApiException} is thrown ({@code UNKNOWN_SETTING}, {@code INVALID_CRON},
     * {@code INVALID_SETTING_VALUE}). Values are normalised before storing (e.g. allowlist uppercased).
     *
     * @return all settings after the update
     */
    Map<String, JsonNode> update(Map<String, JsonNode> changes);
}
