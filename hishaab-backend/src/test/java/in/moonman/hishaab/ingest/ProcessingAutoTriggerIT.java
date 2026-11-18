package in.moonman.hishaab.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** R-015-1: an SMS upload triggers processing after commit, asynchronously (auto-trigger on for this class only). */
@TestPropertySource(properties = "hishaab.processing.auto-trigger=true")
class ProcessingAutoTriggerIT extends IntegrationTest {

    @Test
    void R_015_1_uploadTriggersProcessing() throws InterruptedException {
        JsonNode device = json(postJsonAuthenticated("/api/v1/devices", "{\"name\":\"Trigger phone\"}"));
        String token = device.path("deviceToken").asString();
        String sender = "AX-AXISBK-S";
        String body = "INR 42.00 debited\nA/c no. XX7311\n15-11-26, 11:00:00\nUPI/P2M/512399999999/TRIGGER SHOP\n"
                + "Not you? SMS BLOCKUPI Cust ID to 919000000000\nAxis Bank #" + Ids.newId();
        long receivedAtMs = 1_763_190_000_000L;
        String id = SmsId.of(sender, body, receivedAtMs);
        String json = JSON.writeValueAsString(JSON.createObjectNode().set("messages", JSON.createArrayNode().add(
                JSON.createObjectNode().put("id", id).put("sender", sender).put("body", body)
                        .put("receivedAtMs", receivedAtMs))));

        HttpResponse<String> response = send(request("/api/v1/ingest/sms")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
        assertThat(response.statusCode()).isEqualTo(200);

        String status = "NEW";
        for (int i = 0; i < 100 && !"PARSED".equals(status); i++) {
            Thread.sleep(100);
            status = jdbc.queryForObject("SELECT status FROM raw_message WHERE id = ?", String.class, id);
        }
        assertThat(status).isEqualTo("PARSED");
    }
}
