package in.moonman.hishaab.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.moonman.hishaab.shared.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** R-001-1..3 validation and normalisation per key. */
class SettingDefinitionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode normalise(String key, String jsonValue) {
        return SettingDefinitions.normalise(key, JSON.readTree(jsonValue));
    }

    private static void assertRejected(String key, String jsonValue, String code) {
        assertThatThrownBy(() -> normalise(key, jsonValue))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(code));
    }

    @Test
    void R_001_1_unknownKeyRejected() {
        assertRejected("scan.cronn", "\"0 0 22 * * *\"", "UNKNOWN_SETTING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"0 0 22 * * *\"", "\"0 30 21 * * MON-FRI\"", "\"0 */15 * * * *\""})
    void R_001_2_validSixFieldCronAccepted(String cron) {
        assertThat(normalise("scan.cron", cron).stringValue()).isEqualTo(JSON.readTree(cron).stringValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"bad\"", "\"0 22 * * *\"", "\"0 0 0 22 * * *\"", "\"@daily\"", "\"0 0 25 * * *\"",
            "22", "\"\""})
    void R_001_2_invalidCronRejected(String cron) {
        assertRejected("scan.cron", cron, "INVALID_CRON");
    }

    @Test
    void R_001_3_allowlistUppercasedAndDeduplicated() {
        assertThat(normalise("senders.allowlist", "[\"AXISBK\",\"sbiinb\",\" sbmsms \",\"axisbk\"]"))
                .isEqualTo(JSON.readTree("[\"AXISBK\",\"SBIINB\",\"SBMSMS\"]"));
    }

    @Test
    void R_001_3_wildcardAllowsAllSenders() {
        assertThat(normalise("senders.allowlist", "[\"*\"]")).isEqualTo(JSON.readTree("[\"*\"]"));
        assertThat(normalise("senders.allowlist", "[\" * \",\"axisbk\"]")).isEqualTo(JSON.readTree("[\"*\",\"AXISBK\"]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"**\"]", "[\"AX*\"]", "[\"AX\"]", "[\"TOOLONGID\"]", "[\"AX-AXISBK-S\"]", "[\"AXIS1\"]", "[1]", "\"AXISBK\"",
            "[null]"})
    void R_001_3_badAllowlistEntriesRejected(String value) {
        assertRejected("senders.allowlist", value, "INVALID_SETTING_VALUE");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "scan.serverDelayMinutes     | -1",
            "scan.serverDelayMinutes     | 1.5",
            "scan.serverDelayMinutes     | \"15\"",
            "match.smsEmailWindowMinutes | 0",
            "backfill.days               | 366",
            "ai.categoryConfidenceMin    | 1.01",
            "ai.categoryConfidenceMin    | -0.1",
            "ai.categoryConfidenceMin    | \"0.8\"",
            "scan.zone                   | \"Mars/Olympus\"",
            "scan.zone                   | 5",
            "backfill.days               | null",
    })
    void outOfRangeOrWrongTypeRejected(String key, String value) {
        assertRejected(key, value, "INVALID_SETTING_VALUE");
    }

    @Test
    void validScalarsAccepted() {
        assertThat(normalise("scan.zone", "\"Asia/Kolkata\"").stringValue()).isEqualTo("Asia/Kolkata");
        assertThat(normalise("backfill.days", "60").intValue()).isEqualTo(60);
        assertThat(normalise("ai.categoryConfidenceMin", "0.9").doubleValue()).isEqualTo(0.9);
        assertThat(normalise("scan.serverDelayMinutes", "0").intValue()).isZero();
    }
}
