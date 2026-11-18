package in.moonman.hishaab.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for integration tests: full app on a random port, {@code test} profile, Testcontainers Postgres.
 * Docker must be running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String TEST_USER = "test-user";
    protected static final String TEST_PASSWORD = "test-password";

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Puts every setting back to its V2 migration default. Call after tests that change settings. */
    protected void restoreDefaultSettings() {
        jdbc.update("""
                UPDATE settings s SET value = d.value::jsonb, updated_at = now()
                FROM (VALUES ('scan.cron', '"0 0 22 * * *"'), ('scan.zone', '"Asia/Kolkata"'),
                             ('scan.serverDelayMinutes', '15'), ('senders.allowlist', '[]'),
                             ('match.smsEmailWindowMinutes', '180'), ('ai.categoryConfidenceMin', '0.80'),
                             ('backfill.days', '31')) AS d(key, value)
                WHERE s.key = d.key""");
    }

    protected HttpResponse<String> get(String path) {
        return send(request(path).GET().build());
    }

    protected HttpResponse<String> getAuthenticated(String path) {
        return send(request(path).header("Authorization", basic(TEST_USER, TEST_PASSWORD)).GET().build());
    }

    protected HttpResponse<String> postJsonAuthenticated(String path, String json) {
        return send(request(path)
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    protected HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    protected static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
