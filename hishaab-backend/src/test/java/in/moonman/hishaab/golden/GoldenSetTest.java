package in.moonman.hishaab.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.classify.ClassifyService;
import in.moonman.hishaab.classify.MessageClass;
import in.moonman.hishaab.classify.internal.TestClassifiers;
import in.moonman.hishaab.parse.ParseService;
import in.moonman.hishaab.parse.ParsedFields;
import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.parse.internal.TestParsers;
import in.moonman.hishaab.shared.Source;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC-013 golden set: synthetic messages modelled on real Axis, SBI and fund-house shapes (HLD D-25).
 * Each fixture is classified (AC-012-6) and parsed (AC-013-1); coverage rules are AC-013-2 and the SPEC-013
 * coverage rule (one shape fixture + at least two variants per template).
 */
class GoldenSetTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ClassifyService CLASSIFIER = TestClassifiers.fromClasspath();
    private static final ParseService PARSER = TestParsers.fromClasspath();

    record Fixture(String name, JsonNode doc) {
        @Override
        public String toString() {
            return name;
        }

        JsonNode input() {
            return doc.path("input");
        }

        JsonNode expected() {
            return doc.path("expected");
        }

        String expectedTemplate() {
            JsonNode t = expected().path("templateId");
            return t.isNull() || t.isMissingNode() ? null : t.asString();
        }
    }

    static List<Fixture> fixtures() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:golden/**/*.json");
        assertThat(resources).as("golden fixtures").isNotEmpty();
        return Arrays.stream(resources)
                .map(GoldenSetTest::read)
                .sorted(Comparator.comparing(Fixture::name))
                .toList();
    }

    private static Fixture read(Resource r) {
        try (InputStream in = r.getInputStream()) {
            String path = r.getURL().getPath();
            String name = path.substring(path.indexOf("/golden/") + "/golden/".length());
            return new Fixture(name, JSON.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<ParsedMessage> parse(Fixture f) {
        JsonNode in = f.input();
        return PARSER.parse(Source.valueOf(in.path("source").asString()), in.path("sender").asString(),
                in.path("body").asString(), Instant.ofEpochMilli(in.path("receivedAtMs").asLong()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void AC_012_06_classifiedAsFixtureSays(Fixture f) {
        MessageClass actual = CLASSIFIER.classify(f.input().path("sender").asString(), f.input().path("body").asString())
                .messageClass();

        assertThat(actual).isEqualTo(MessageClass.valueOf(f.expected().path("class").asString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void AC_013_01_parsedExactlyAsFixtureSays(Fixture f) {
        Optional<ParsedMessage> parsed = parse(f);
        String expectedTemplate = f.expectedTemplate();

        if (expectedTemplate == null) {
            assertThat(parsed).as("R-013-4: no template may match; it goes to the unparsed/ignored path").isEmpty();
            return;
        }
        assertThat(parsed).as("a template must match").isPresent();
        assertThat(parsed.get().templateId()).isEqualTo(expectedTemplate);
        assertThat(parsed.get().confidence()).isEqualTo(1.0);

        JsonNode e = f.expected().path("fields");
        ParsedFields actual = parsed.get().fields();
        assertThat(actual.amountPaise()).as("amountPaise").isEqualTo(e.path("amountPaise").asLong());
        assertThat(actual.direction().name()).as("direction").isEqualTo(e.path("direction").asString());
        assertThat(actual.accountLast4()).as("accountLast4").isEqualTo(textOrNull(e, "accountLast4"));
        assertThat(actual.channel().name()).as("channel").isEqualTo(e.path("channel").asString());
        assertThat(actual.counterpartyRaw()).as("counterpartyRaw").isEqualTo(textOrNull(e, "counterpartyRaw"));
        assertThat(actual.refNo()).as("refNo").isEqualTo(textOrNull(e, "refNo"));
        assertThat(actual.txnTime()).as("txnTime")
                .isEqualTo(OffsetDateTime.parse(e.path("txnTime").asString()).toInstant());
        assertThat(actual.balanceAfterPaise()).as("balanceAfterPaise")
                .isEqualTo(e.path("balanceAfterPaise").isNull() ? null : e.path("balanceAfterPaise").asLong());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void R_013_6_investmentConfirmationsAreNeverTransactionRecords(Fixture f) {
        Optional<ParsedMessage> parsed = parse(f);
        boolean isAllotment = "INVESTMENT_CONFIRMATION".equals(f.expected().path("class").asString());

        parsed.ifPresent(p -> assertThat(p.recordType() == ParsedMessage.RecordType.INVESTMENT_CONFIRMATION)
                .as("record type follows the class").isEqualTo(isAllotment));
    }

    @Test
    void AC_013_02_everyTemplateIsUsedByAShapeFixturePlusTwoVariants() throws IOException {
        Map<String, Long> uses = fixtures().stream()
                .map(Fixture::expectedTemplate)
                .filter(t -> t != null)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(TestParsers.templateIds()).allSatisfy(id ->
                assertThat(uses.getOrDefault(id, 0L)).as("fixtures for template " + id).isGreaterThanOrEqualTo(3));
        assertThat(uses.keySet()).as("fixtures must reference real templates")
                .isSubsetOf(TestParsers.templateIds());
    }

    @Test
    void fixturesAreSynthetic() throws IOException {
        // D-25 guard: fixtures must not contain obviously personal data. Fake values use reserved/neutral forms.
        for (Fixture f : fixtures()) {
            String body = f.input().path("body").asString();
            assertThat(body).as(f.name()).doesNotContainPattern("[A-Za-z0-9._%+-]+@gmail\\.com");
            assertThat(body).as(f.name()).doesNotContainPattern("https?://(?!example\\.invalid)[^\\s]+");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }
}
