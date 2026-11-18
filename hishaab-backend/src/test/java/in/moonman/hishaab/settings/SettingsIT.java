package in.moonman.hishaab.settings;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/** SPEC-001. Every test that changes settings restores the migration defaults afterwards. */
@Import(SettingsIT.EventRecorder.class)
class SettingsIT extends IntegrationTest {

    @Autowired
    SettingsService settings;

    @Autowired
    EventRecorder recorder;

    @BeforeEach
    void clearEvents() {
        recorder.events.clear();
    }

    @AfterEach
    void restoreDefaults() {
        restoreDefaultSettings();
    }

    @Test
    void AC_001_01_freshDbReturnsAllDefaults() {
        HttpResponse<String> response = getAuthenticated("/api/v1/settings");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).isEqualTo(JSON.readTree("""
                {"scan.cron":"0 0 22 * * *","scan.zone":"Asia/Kolkata","scan.serverDelayMinutes":15,
                 "senders.allowlist":["*"],"match.smsEmailWindowMinutes":180,"ai.categoryConfidenceMin":0.80,
                 "backfill.days":31}"""));
    }

    @Test
    void AC_001_01_typedGettersReadDefaults() {
        assertThat(settings.scanCron()).isEqualTo("0 0 22 * * *");
        assertThat(settings.scanZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(settings.scanServerDelayMinutes()).isEqualTo(15);
        assertThat(settings.senderAllowlist()).containsExactly("*");
        assertThat(settings.matchSmsEmailWindowMinutes()).isEqualTo(180);
        assertThat(settings.aiCategoryConfidenceMin()).isEqualTo(0.80);
        assertThat(settings.backfillDays()).isEqualTo(31);
    }

    @Test
    void AC_001_02_invalidCronIs400AndValueUnchanged() {
        HttpResponse<String> response = patch("{\"scan.cron\":\"bad\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("INVALID_CRON");
        assertThat(settings.scanCron()).isEqualTo("0 0 22 * * *");
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void AC_001_03_allowlistIsUppercased() {
        HttpResponse<String> response = patch("{\"senders.allowlist\":[\"AXISBK\",\"sbiinb\"]}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("senders.allowlist")).isEqualTo(JSON.readTree("[\"AXISBK\",\"SBIINB\"]"));
        assertThat(settings.senderAllowlist()).containsExactly("AXISBK", "SBIINB");
    }

    @Test
    void AC_001_04_successfulPatchPublishesSettingsChanged() {
        HttpResponse<String> response = patch("{\"scan.cron\":\"0 30 21 * * *\",\"backfill.days\":45}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(recorder.events).containsExactlyInAnyOrder(
                new SettingsChanged("scan.cron"), new SettingsChanged("backfill.days"));
        assertThat(settings.scanCron()).isEqualTo("0 30 21 * * *");
        assertThat(settings.backfillDays()).isEqualTo(45);
    }

    @Test
    void R_001_4_patchWithSameValuePublishesNothing() {
        patch("{\"backfill.days\":31}");

        assertThat(recorder.events).isEmpty();
    }

    @Test
    void R_001_1_unknownKeyIs400AndNothingChanges() {
        HttpResponse<String> response = patch("{\"backfill.days\":40,\"no.such.key\":1}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("UNKNOWN_SETTING");
        assertThat(settings.backfillDays()).isEqualTo(31);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void invalidValueIs400InvalidSettingValue() {
        HttpResponse<String> response = patch("{\"senders.allowlist\":[\"AX\"]}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("INVALID_SETTING_VALUE");
        assertThat(settings.senderAllowlist()).containsExactly("*");
    }

    @Test
    void settingsRequireAuthentication() {
        assertThat(get("/api/v1/settings").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> patch(String body) {
        return send(request("/api/v1/settings")
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    @TestConfiguration
    static class EventRecorder {
        final List<SettingsChanged> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(SettingsChanged event) {
            events.add(event);
        }
    }
}
