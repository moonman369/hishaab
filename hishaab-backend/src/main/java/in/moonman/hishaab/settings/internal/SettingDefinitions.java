package in.moonman.hishaab.settings.internal;

import static in.moonman.hishaab.settings.SettingsService.AI_CATEGORY_CONFIDENCE_MIN;
import static in.moonman.hishaab.settings.SettingsService.BACKFILL_DAYS;
import static in.moonman.hishaab.settings.SettingsService.MATCH_SMS_EMAIL_WINDOW_MINUTES;
import static in.moonman.hishaab.settings.SettingsService.SCAN_CRON;
import static in.moonman.hishaab.settings.SettingsService.SCAN_SERVER_DELAY_MINUTES;
import static in.moonman.hishaab.settings.SettingsService.SCAN_ZONE;
import static in.moonman.hishaab.settings.SettingsService.SENDERS_ALLOWLIST;

import in.moonman.hishaab.settings.SettingsService;
import in.moonman.hishaab.shared.ApiException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The known setting keys and how each value is validated and normalised (R-001-1..3).
 * Each normaliser returns the value to store or throws an {@link ApiException}.
 */
final class SettingDefinitions {

    static final String UNKNOWN_SETTING = "UNKNOWN_SETTING";
    static final String INVALID_CRON = "INVALID_CRON";
    static final String INVALID_SETTING_VALUE = "INVALID_SETTING_VALUE";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern SENDER_PART = Pattern.compile("[A-Z]{3,8}");

    /** Insertion order is the order returned by {@code GET /settings}. */
    private static final Map<String, UnaryOperator<JsonNode>> NORMALISERS = new LinkedHashMap<>();

    static {
        NORMALISERS.put(SCAN_CRON, SettingDefinitions::cron);
        NORMALISERS.put(SCAN_ZONE, SettingDefinitions::zone);
        NORMALISERS.put(SCAN_SERVER_DELAY_MINUTES, v -> intInRange(SCAN_SERVER_DELAY_MINUTES, v, 0, 720));
        NORMALISERS.put(SENDERS_ALLOWLIST, SettingDefinitions::allowlist);
        NORMALISERS.put(MATCH_SMS_EMAIL_WINDOW_MINUTES, v -> intInRange(MATCH_SMS_EMAIL_WINDOW_MINUTES, v, 1, 1440));
        NORMALISERS.put(AI_CATEGORY_CONFIDENCE_MIN, SettingDefinitions::probability);
        NORMALISERS.put(BACKFILL_DAYS, v -> intInRange(BACKFILL_DAYS, v, 1, 365));
    }

    private SettingDefinitions() {
    }

    static Set<String> keys() {
        return NORMALISERS.keySet();
    }

    static JsonNode normalise(String key, JsonNode value) {
        UnaryOperator<JsonNode> normaliser = NORMALISERS.get(key);
        if (normaliser == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UNKNOWN_SETTING, "unknown setting '" + key + "'");
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw invalid(key, "must not be null");
        }
        return normaliser.apply(value);
    }

    /** R-001-2: a Spring 6-field cron (second minute hour day-of-month month day-of-week). */
    private static JsonNode cron(JsonNode value) {
        String text = value.isString() ? value.stringValue().strip() : null;
        if (text == null || text.split("\\s+").length != 6) {
            throw cronError();
        }
        try {
            CronExpression.parse(text);
        } catch (IllegalArgumentException e) {
            throw cronError();
        }
        return NODES.stringNode(text);
    }

    private static ApiException cronError() {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_CRON,
                SCAN_CRON + " must be a Spring 6-field cron expression, e.g. \"0 0 22 * * *\"");
    }

    private static JsonNode zone(JsonNode value) {
        if (!value.isString()) {
            throw invalid(SCAN_ZONE, "must be a time-zone ID such as \"Asia/Kolkata\"");
        }
        try {
            return NODES.stringNode(ZoneId.of(value.stringValue().strip()).getId());
        } catch (DateTimeException e) {
            throw invalid(SCAN_ZONE, "must be a time-zone ID such as \"Asia/Kolkata\"");
        }
    }

    /**
     * R-001-3: each entry is the stable middle part of a sender ID, uppercased, 3–8 letters, or the wildcard
     * {@value SettingsService#ALLOW_ALL_SENDERS} (accept every sender). Duplicates collapse.
     */
    private static JsonNode allowlist(JsonNode value) {
        if (!value.isArray()) {
            throw invalid(SENDERS_ALLOWLIST, "must be an array of sender ID parts such as [\"AXISBK\"]");
        }
        Set<String> parts = new LinkedHashSet<>();
        for (JsonNode entry : value) {
            String part = entry.isString() ? entry.stringValue().strip().toUpperCase(Locale.ROOT) : "";
            if (!part.equals(SettingsService.ALLOW_ALL_SENDERS) && !SENDER_PART.matcher(part).matches()) {
                throw invalid(SENDERS_ALLOWLIST,
                        "entries must be 3-8 letters (the middle part of a sender ID like AX-AXISBK-S) or \"*\"");
            }
            parts.add(part);
        }
        ArrayNode result = NODES.arrayNode();
        parts.forEach(result::add);
        return result;
    }

    private static JsonNode intInRange(String key, JsonNode value, int min, int max) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw invalid(key, "must be a whole number from " + min + " to " + max);
        }
        return NODES.numberNode(value.intValue());
    }

    private static JsonNode probability(JsonNode value) {
        if (!value.isNumber()) {
            throw invalid(AI_CATEGORY_CONFIDENCE_MIN, "must be a number from 0 to 1");
        }
        BigDecimal number = value.decimalValue();
        if (number.compareTo(BigDecimal.ZERO) < 0 || number.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(AI_CATEGORY_CONFIDENCE_MIN, "must be a number from 0 to 1");
        }
        return NODES.numberNode(number);
    }

    private static ApiException invalid(String key, String why) {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_SETTING_VALUE, key + " " + why);
    }
}
