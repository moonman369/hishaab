package in.moonman.hishaab.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC §4.1 contract. The vectors in {@code contract/sms-id-vectors.json} are shared with the Android worker;
 * their expected IDs were generated independently (Python hashlib), so this is not a self-check.
 */
class SmsIdTest {

    static Stream<JsonNode> vectors() throws IOException {
        try (InputStream in = SmsIdTest.class.getResourceAsStream("/contract/sms-id-vectors.json")) {
            JsonNode doc = JsonMapper.builder().build().readTree(in);
            assertThat(doc.path("vectors").size()).isGreaterThanOrEqualTo(3);
            return doc.path("vectors").values().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void sharedVectorsMatch(JsonNode v) {
        String id = SmsId.of(v.path("sender").asString(), v.path("body").asString(), v.path("receivedAtMs").asLong());

        assertThat(id).isEqualTo(v.path("expectedId").asString());
    }
}
