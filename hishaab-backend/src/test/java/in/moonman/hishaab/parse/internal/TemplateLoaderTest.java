package in.moonman.hishaab.parse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.shared.Source;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** Template YAML validation (R-013-1..3) and engine rules (R-013-2). */
class TemplateLoaderTest {

    private static List<ParseTemplate> load(String yaml) {
        return TemplateLoader.load(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test.yml";
            }
        });
    }

    private static final String OK = """
            templates:
              - id: t.one
                version: 1
                bank: AXIS
                source: SMS
                accountType: SAVINGS
                recordType: TRANSACTION
                senderPattern: 'AXISBK'
                regex: 'INR (?<amount>[\\d.]+) (?<dir>debited|credited)'
              - id: t.two
                version: 1
                bank: AXIS
                source: SMS
                accountType: SAVINGS
                recordType: TRANSACTION
                senderPattern: 'AXISBK'
                regex: 'INR (?<amount>[\\d.]+) debited'
                fixed: { direction: DR }
            """;

    @Test
    void shippedTemplatesLoad() {
        assertThat(TemplateLoader.loadFromClasspath()).isNotEmpty();
    }

    @Test
    void R_013_2_firstMatchingTemplateInFileOrderWins() {
        TemplateEngine engine = new TemplateEngine(load(OK));

        var parsed = engine.parse(Source.SMS, "AX-AXISBK-S", "INR 5.00 debited", Instant.now());

        assertThat(parsed).map(ParsedMessage::templateId).contains("t.one");
    }

    @Test
    void R_013_2_senderMustMatch() {
        TemplateEngine engine = new TemplateEngine(load(OK));

        assertThat(engine.parse(Source.SMS, "VM-SBIINB-S", "INR 5.00 debited", Instant.now())).isEmpty();
        assertThat(engine.parse(Source.EMAIL, "AX-AXISBK-S", "INR 5.00 debited", Instant.now())).isEmpty();
    }

    @Test
    void R_013_3_captureThatDoesNotNormaliseMeansNoMatch() {
        TemplateEngine engine = new TemplateEngine(load(OK));

        // "5.123" has three decimals: Money rejects it, so t.one and t.two both fail to normalise.
        assertThat(engine.parse(Source.SMS, "AX-AXISBK-S", "INR 5.123 debited", Instant.now())).isEmpty();
    }

    @Test
    void R_013_3_templateWithoutAmountGroupIsRejected() {
        assertThatThrownBy(() -> load(OK.replace("(?<amount>[\\d.]+) (?<dir>", "([\\d.]+) (?<dir>")))
                .hasMessageContaining("amount");
    }

    @Test
    void R_013_3_templateWithoutDirectionIsRejected() {
        assertThatThrownBy(() -> load(OK.replace("fixed: { direction: DR }", "")))
                .hasMessageContaining("dir");
    }

    @Test
    void unknownFixedFieldIsRejected() {
        assertThatThrownBy(() -> load(OK.replace("fixed: { direction: DR }", "fixed: { direction: DR, amount: 5 }")))
                .hasMessageContaining("fixed");
    }

    @Test
    void transactionTemplateNeedsBankAndAccountType() {
        assertThatThrownBy(() -> load(OK.replaceFirst("\\n\\s+accountType: SAVINGS", "")))
                .hasMessageContaining("accountType");
    }

    @Test
    void hashChangesWhenRegexChanges() {
        ParseTemplate a = load(OK).getFirst();
        ParseTemplate b = load(OK.replace("debited|credited", "debited|credited|spent")).getFirst();

        assertThat(a.regexHash()).isNotEqualTo(b.regexHash());
        assertThat(a.regexHash()).isEqualTo(load(OK).getFirst().regexHash());
    }
}
