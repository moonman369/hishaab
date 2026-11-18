package in.moonman.hishaab.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** SPEC-011: SMS ingest. raw_message rows are append-only, so every test uses unique message bodies. */
@Import(IngestSmsIT.EventRecorder.class)
class IngestSmsIT extends IntegrationTest {

    private static final long T0 = 1_763_400_000_000L;

    @Autowired
    EventRecorder recorder;

    private String deviceToken;
    private UUID deviceId;
    private String nonce;

    @BeforeEach
    void setUp() {
        JsonNode created = json(postJsonAuthenticated("/api/v1/devices", "{\"name\":\"Ingest phone\"}"));
        deviceToken = created.path("deviceToken").asString();
        deviceId = UUID.fromString(created.path("deviceId").asString());
        jdbc.update("UPDATE settings SET value = '[\"AXISBK\",\"SBIINB\"]'::jsonb WHERE key = 'senders.allowlist'");
        nonce = Ids.newId().toString();
        recorder.events.clear();
    }

    @AfterEach
    void restoreDefaults() {
        restoreDefaultSettings();
    }

    @Test
    void AC_011_01_threeValidMessagesAreAcceptedStoredAndAnnounced() {
        List<ObjectNode> batch = List.of(sms("AX-AXISBK-S", "one", T0), sms("VM-SBIINB-S", "two", T0 + 1),
                sms("AX-AXISBK-T", "three", T0 + 2));

        HttpResponse<String> response = upload(batch);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(ids(body.path("accepted"))).containsExactlyElementsOf(idsOf(batch));
        assertThat(body.path("duplicate").size()).isZero();
        assertThat(body.path("rejected").size()).isZero();
        assertThat(body.path("cursorMs").asLong()).isEqualTo(T0 + 2);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, source, status, device_id, received_at FROM raw_message WHERE device_id = ?", deviceId);
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row.get("source")).isEqualTo("SMS");
            assertThat(row.get("status")).isEqualTo("NEW");
            assertThat(row.get("device_id")).isEqualTo(deviceId);
        });
        assertThat(recorder.events).extracting(RawMessageReceived::rawMessageId)
                .containsExactlyInAnyOrderElementsOf(idsOf(batch));
    }

    @Test
    void AC_011_01_bodyAndTimeStoredExactly() {
        ObjectNode message = sms("AX-AXISBK-S", " spaced \r\n ₹1,234 ", T0 + 123);

        upload(List.of(message));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT sender, body, (extract(epoch FROM received_at) * 1000)::bigint AS ms FROM raw_message WHERE id = ?",
                message.path("id").asString());
        assertThat(row.get("sender")).isEqualTo("AX-AXISBK-S");
        assertThat(row.get("body")).isEqualTo(message.path("body").asString());
        assertThat(row.get("ms")).isEqualTo(T0 + 123);
    }

    @Test
    void AC_011_02_sameBatchTwiceIsAllDuplicateWithNoNewRows() {
        List<ObjectNode> batch = List.of(sms("AX-AXISBK-S", "a", T0), sms("AX-AXISBK-S", "b", T0 + 1),
                sms("AX-AXISBK-S", "c", T0 + 2));
        upload(batch);
        recorder.events.clear();

        JsonNode second = json(upload(batch));

        assertThat(second.path("accepted").size()).isZero();
        assertThat(ids(second.path("duplicate"))).containsExactlyElementsOf(idsOf(batch));
        assertThat(second.path("cursorMs").asLong()).isEqualTo(T0 + 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_message WHERE device_id = ?", Integer.class,
                deviceId)).isEqualTo(3);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void R_011_4_repeatedIdWithinOneBatchIsStoredOnce() {
        ObjectNode message = sms("AX-AXISBK-S", "twice", T0);

        JsonNode body = json(upload(List.of(message, message.deepCopy())));

        assertThat(ids(body.path("accepted"))).containsExactly(message.path("id").asString());
        assertThat(ids(body.path("duplicate"))).containsExactly(message.path("id").asString());
    }

    @Test
    void AC_011_03_wrongIdIsRejectedOthersAccepted() {
        ObjectNode good1 = sms("AX-AXISBK-S", "good1", T0);
        ObjectNode bad = sms("AX-AXISBK-S", "bad", T0 + 1);
        bad.put("id", Ids.sha256Hex("something else " + nonce));
        ObjectNode good2 = sms("AX-AXISBK-S", "good2", T0 + 2);

        JsonNode body = json(upload(List.of(good1, bad, good2)));

        assertThat(ids(body.path("accepted"))).containsExactly(good1.path("id").asString(), good2.path("id").asString());
        assertThat(body.path("rejected").size()).isEqualTo(1);
        assertThat(body.path("rejected").get(0).path("id").asString()).isEqualTo(bad.path("id").asString());
        assertThat(body.path("rejected").get(0).path("reason").asString()).isEqualTo("HASH_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_message WHERE id = ?", Integer.class,
                bad.path("id").asString())).isZero();
    }

    @Test
    void AC_011_04_senderNotInAllowlistIsRejected() {
        jdbc.update("UPDATE settings SET value = '[\"AXISBK\"]'::jsonb WHERE key = 'senders.allowlist'");
        ObjectNode promo = sms("VM-PROMOS", "50% off!", T0);
        ObjectNode bank = sms("AX-AXISBK-S", "bank", T0 + 1);

        JsonNode body = json(upload(List.of(promo, bank)));

        assertThat(ids(body.path("accepted"))).containsExactly(bank.path("id").asString());
        assertThat(body.path("rejected").get(0).path("reason").asString()).isEqualTo("SENDER_NOT_ALLOWED");
        assertThat(body.path("cursorMs").asLong()).isEqualTo(T0 + 1);
    }

    @Test
    void AC_011_07_wildcardAllowlistAcceptsAnySender() {
        jdbc.update("UPDATE settings SET value = '[\"*\"]'::jsonb WHERE key = 'senders.allowlist'");
        ObjectNode fundHouse = sms("VK-ALPHMF-S", "Your SIP Purchase of Rs.999.95 is processed", T0);
        ObjectNode bank = sms("AX-AXISBK-S", "bank", T0 + 1);

        JsonNode body = json(upload(List.of(fundHouse, bank)));

        assertThat(ids(body.path("accepted"))).containsExactly(fundHouse.path("id").asString(),
                bank.path("id").asString());
        assertThat(body.path("rejected").size()).isZero();
    }

    @Test
    void AC_011_05_olderBatchDoesNotMoveCursorBack() {
        upload(List.of(sms("AX-AXISBK-S", "new", T0 + 10_000)));

        JsonNode body = json(upload(List.of(sms("AX-AXISBK-S", "old", T0))));

        assertThat(body.path("accepted").size()).isEqualTo(1);
        assertThat(body.path("cursorMs").asLong()).isEqualTo(T0 + 10_000);
        assertThat(jdbc.queryForObject("SELECT sms_cursor_ms FROM device WHERE id = ?", Long.class, deviceId))
                .isEqualTo(T0 + 10_000);
    }

    @Test
    void R_011_6_cursorAndLastSyncSavedOnDevice() {
        upload(List.of(sms("AX-AXISBK-S", "x", T0 + 5)));

        Map<String, Object> row = jdbc.queryForMap("SELECT sms_cursor_ms, last_sync_at FROM device WHERE id = ?",
                deviceId);
        assertThat(row.get("sms_cursor_ms")).isEqualTo(T0 + 5);
        assertThat(row.get("last_sync_at")).isNotNull();
    }

    @Test
    void R_011_6_rejectedMessagesDoNotMoveCursor() {
        JsonNode body = json(upload(List.of(sms("XX-NOTBANK-S", "promo", T0 + 99_999))));

        assertThat(body.path("rejected").size()).isEqualTo(1);
        assertThat(body.path("cursorMs").isNull()).isTrue();
    }

    @Test
    void AC_011_06_501MessagesIs400BatchTooLarge() {
        List<ObjectNode> batch = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            batch.add(sms("AX-AXISBK-S", "m" + i, T0 + i));
        }

        HttpResponse<String> response = upload(batch);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_message WHERE device_id = ?", Integer.class,
                deviceId)).isZero();
    }

    @Test
    void R_011_1_fiveHundredMessagesAccepted() {
        List<ObjectNode> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            batch.add(sms("AX-AXISBK-S", "m" + i, T0 + i));
        }

        JsonNode body = json(upload(batch));

        assertThat(body.path("accepted").size()).isEqualTo(500);
    }

    @Test
    void R_011_1_emptyBatchIs400() {
        HttpResponse<String> response = upload(List.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("EMPTY_BATCH");
    }

    @Test
    void R_011_7_malformedItemIsRejectedNotFatal() {
        ObjectNode good = sms("AX-AXISBK-S", "fine", T0);
        ObjectNode noBody = sms("AX-AXISBK-S", "nobody", T0 + 1);
        noBody.remove("body");
        ObjectNode nul = sms("AX-AXISBK-S", "nul\u0000char", T0 + 2);

        JsonNode body = json(upload(List.of(good, noBody, nul)));

        assertThat(ids(body.path("accepted"))).containsExactly(good.path("id").asString());
        assertThat(body.path("rejected").values()).extracting(r -> r.path("reason").asString())
                .containsExactly("INVALID_MESSAGE", "INVALID_MESSAGE");
    }

    @Test
    void userCredentialsCannotIngest() {
        HttpResponse<String> response = postJsonAuthenticated("/api/v1/ingest/sms", "{\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private ObjectNode sms(String sender, String bodyText, long receivedAtMs) {
        String body = bodyText + " #" + nonce;
        ObjectNode node = JSON.createObjectNode();
        node.put("id", SmsId.of(sender, body, receivedAtMs));
        node.put("sender", sender);
        node.put("body", body);
        node.put("receivedAtMs", receivedAtMs);
        return node;
    }

    private HttpResponse<String> upload(List<ObjectNode> messages) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode array = root.putArray("messages");
        messages.forEach(array::add);
        return send(request("/api/v1/ingest/sms")
                .header("Authorization", "Bearer " + deviceToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(root)))
                .build());
    }

    private static List<String> ids(JsonNode array) {
        return array.values().stream().map(JsonNode::asString).toList();
    }

    private static List<String> idsOf(List<ObjectNode> messages) {
        return messages.stream().map(m -> m.path("id").asString()).toList();
    }

    @TestConfiguration
    static class EventRecorder {
        final List<RawMessageReceived> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(RawMessageReceived event) {
            events.add(event);
        }
    }
}
