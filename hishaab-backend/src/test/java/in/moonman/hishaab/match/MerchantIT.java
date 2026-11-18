package in.moonman.hishaab.match;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.match.MerchantService.MerchantRef;
import in.moonman.hishaab.match.MerchantService.MerchantType;
import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** SPEC-030 merchants. Names carry a random letter tag so tests don't collide in the shared DB. */
@Import(MerchantIT.Recorder.class)
class MerchantIT extends IntegrationTest {

    @Autowired
    MerchantService merchants;

    @Autowired
    Recorder recorder;

    private String tag;

    @BeforeEach
    void setUp() {
        recorder.merged.clear();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        tag = sb.toString();
    }

    @Test
    void AC_030_01_variantsResolveToOneStoredMerchant() {
        MerchantRef a = merchants.resolve(tag + " FOODS*BLR").orElseThrow();
        MerchantRef b = merchants.resolve(tag + " Foods Pvt Ltd").orElseThrow();

        assertThat(a.created()).isTrue();
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.created()).isFalse();
    }

    @Test
    void AC_030_02_personalUpiIdIsStoredAsPerson() {
        MerchantRef p = merchants.resolve(tag.toLowerCase(Locale.ROOT) + ".k@okaxis").orElseThrow();

        assertThat(p.type()).isEqualTo(MerchantType.PERSON);
    }

    @Test
    void AC_030_03_mergeMovesAliasesAndDeletesTheOldMerchant() {
        MerchantRef from = merchants.resolve(tag + " KITCHEN").orElseThrow();
        MerchantRef into = merchants.resolve(tag + " KITCHEN EXPRESS").orElseThrow();

        HttpResponse<String> response = send(request("/api/v1/merchants/" + from.id() + "/merge-into/" + into.id())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("aliases").values()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant WHERE id = ?", Integer.class, from.id())).isZero();
        assertThat(recorder.merged).containsExactly(new MerchantMerged(from.id(), into.id()));
        assertThat(merchants.resolve(tag + " KITCHEN").orElseThrow().id()).isEqualTo(into.id());
    }

    @Test
    void wrongTypeGuessCanBeCorrectedAndSearched() {
        MerchantRef m = merchants.resolve(tag + " CONN").orElseThrow(); // two plain words: guessed PERSON

        HttpResponse<String> patched = send(request("/api/v1/merchants/" + m.id())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"type\":\"MERCHANT\",\"alwaysAsk\":true}"))
                .build());

        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(json(patched).path("type").asString()).isEqualTo("MERCHANT");
        assertThat(json(patched).path("alwaysAsk").asBoolean()).isTrue();
        JsonNode found = json(getAuthenticated("/api/v1/merchants?q=" + tag.toLowerCase(Locale.ROOT)));
        assertThat(found.values()).extracting(n -> n.path("id").asString()).contains(m.id().toString());
    }

    @Test
    void mergeIntoSelfIs400AndUnknownIs404() {
        MerchantRef m = merchants.resolve(tag + " MART").orElseThrow();

        HttpResponse<String> self = send(request("/api/v1/merchants/" + m.id() + "/merge-into/" + m.id())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD)).POST(HttpRequest.BodyPublishers.noBody()).build());
        HttpResponse<String> unknown = send(request("/api/v1/merchants/" + java.util.UUID.randomUUID() + "/merge-into/"
                + m.id()).header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(json(self).path("code").asString()).isEqualTo("MERGE_SELF");
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    void unusableRawNameResolvesToNothing() {
        assertThat(merchants.resolve("123456")).isEmpty();
        assertThat(merchants.resolve(null)).isEmpty();
    }

    @TestConfiguration
    static class Recorder {
        final List<MerchantMerged> merged = new CopyOnWriteArrayList<>();

        @EventListener
        void on(MerchantMerged e) {
            merged.add(e);
        }
    }
}
