package in.moonman.hishaab.device;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SPEC-010: devices and device tokens. */
class DeviceIT extends IntegrationTest {

    @AfterEach
    void restoreDefaults() {
        restoreDefaultSettings();
    }

    @Test
    void AC_010_01_createdTokenStartsWithHdtAndOnlyItsHashIsStored() {
        HttpResponse<String> response = postJsonAuthenticated("/api/v1/devices", "{\"name\":\"Pixel 8\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json(response);
        String token = body.path("deviceToken").asString();
        UUID id = UUID.fromString(body.path("deviceId").asString());

        assertThat(token).startsWith("hdt_");
        // 32 random bytes as unpadded base64url = 43 chars
        assertThat(token.substring(4)).matches("[A-Za-z0-9_-]{43}");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM device WHERE id = ?", id);
        assertThat(row.get("token_hash")).isEqualTo(Ids.sha256Hex(token));
        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(token.substring(4)));
    }

    @Test
    void AC_010_01_listNeverReturnsTokens() {
        String token = registerDevice("Listed phone");

        HttpResponse<String> response = getAuthenticated("/api/v1/devices");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Listed phone").doesNotContain(token.substring(4));
        assertThat(response.body()).doesNotContainIgnoringCase("token");
    }

    @Test
    void AC_010_02_deviceTokenOnTransactionsIs403() {
        String token = registerDevice("Phone");

        HttpResponse<String> response = send(withDevice(request("/api/v1/transactions"), token).GET().build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json(response).path("code").asString()).isEqualTo("FORBIDDEN");
    }

    @Test
    void R_010_3_deviceCannotManageDevicesOrSettings() {
        String token = registerDevice("Phone");

        assertThat(send(withDevice(request("/api/v1/devices"), token).GET().build()).statusCode()).isEqualTo(403);
        assertThat(send(withDevice(request("/api/v1/settings"), token).GET().build()).statusCode()).isEqualTo(403);
        assertThat(send(withDevice(request("/api/v1/devices"), token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void R_010_3_userCannotCallDeviceEndpoints() {
        assertThat(getAuthenticated("/api/v1/device/config").statusCode()).isEqualTo(403);
    }

    @Test
    void AC_010_03_revokedTokenIs401() {
        HttpResponse<String> created = postJsonAuthenticated("/api/v1/devices", "{\"name\":\"Old phone\"}");
        String token = json(created).path("deviceToken").asString();
        String id = json(created).path("deviceId").asString();
        assertThat(send(withDevice(request("/api/v1/device/config"), token).GET().build()).statusCode())
                .isEqualTo(200);

        HttpResponse<String> revoked = send(request("/api/v1/devices/" + id)
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD)).DELETE().build());
        assertThat(revoked.statusCode()).isEqualTo(204);

        HttpResponse<String> after = send(withDevice(request("/api/v1/device/config"), token).GET().build());
        assertThat(after.statusCode()).isEqualTo(401);
        assertThat(json(after).path("code").asString()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void R_010_4_unknownDeviceTokenIs401() {
        HttpResponse<String> response = send(withDevice(request("/api/v1/device/config"),
                "hdt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void revokingUnknownDeviceIs404() {
        HttpResponse<String> response = send(request("/api/v1/devices/" + Ids.newId())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD)).DELETE().build());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("code").asString()).isEqualTo("DEVICE_NOT_FOUND");
    }

    @Test
    void AC_010_04_configReturnsCurrentSettingsAndCursor() {
        HttpResponse<String> created = postJsonAuthenticated("/api/v1/devices", "{\"name\":\"Phone\"}");
        String token = json(created).path("deviceToken").asString();
        UUID id = UUID.fromString(json(created).path("deviceId").asString());
        send(request("/api/v1/settings")
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        "{\"senders.allowlist\":[\"AXISBK\",\"SBIINB\"],\"scan.cron\":\"0 15 21 * * *\"}"))
                .build());

        JsonNode first = json(send(withDevice(request("/api/v1/device/config"), token).GET().build()));
        assertThat(first.path("cursorMs").isNull()).isTrue();

        jdbc.update("UPDATE device SET sms_cursor_ms = ? WHERE id = ?", 1_763_400_000_000L, id);
        HttpResponse<String> response = send(withDevice(request("/api/v1/device/config"), token).GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode config = json(response);
        assertThat(config.path("senderAllowlist").values()).extracting(JsonNode::asString)
                .isEqualTo(List.of("AXISBK", "SBIINB"));
        assertThat(config.path("scanCron").asString()).isEqualTo("0 15 21 * * *");
        assertThat(config.path("scanZone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(config.path("backfillDays").asInt()).isEqualTo(31);
        assertThat(config.path("cursorMs").asLong()).isEqualTo(1_763_400_000_000L);
    }

    @Test
    void deviceCanSetItsFcmToken() {
        HttpResponse<String> created = postJsonAuthenticated("/api/v1/devices", "{\"name\":\"Phone\"}");
        String token = json(created).path("deviceToken").asString();
        UUID id = UUID.fromString(json(created).path("deviceId").asString());

        HttpResponse<String> response = send(withDevice(request("/api/v1/devices/me"), token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"fcmToken\":\"fcm-123\"}")).build());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT fcm_token FROM device WHERE id = ?", String.class, id))
                .isEqualTo("fcm-123");
    }

    @Test
    void blankDeviceNameIs400() {
        HttpResponse<String> response = postJsonAuthenticated("/api/v1/devices", "{\"name\":\"  \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    private String registerDevice(String name) {
        return json(postJsonAuthenticated("/api/v1/devices", "{\"name\":\"" + name + "\"}"))
                .path("deviceToken").asString();
    }

    private static HttpRequest.Builder withDevice(HttpRequest.Builder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
