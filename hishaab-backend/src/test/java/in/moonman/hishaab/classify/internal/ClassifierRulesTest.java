package in.moonman.hishaab.classify.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.moonman.hishaab.classify.MessageClass;
import in.moonman.hishaab.classify.internal.ClassifierRules.RuleSet;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** Loader for classify/*.yml: fragments, bank overlays and fail-fast validation. */
class ClassifierRulesTest {

    private static RuleSet load(String yaml) {
        return ClassifierRules.load(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test.yml";
            }
        });
    }

    @Test
    void shippedRulesLoadAndCoverEveryCheckedClass() {
        List<RuleSet> sets = ClassifierRules.loadFromClasspath();

        RuleSet common = sets.stream().filter(RuleSet::isCommon).findFirst().orElseThrow();
        assertThat(common.byClass().keySet()).containsExactlyInAnyOrderElementsOf(MessageClass.CHECK_ORDER);
    }

    @Test
    void fragmentsAreExpanded() {
        RuleSet set = load("""
                fragments:
                  amt: 'rs \\d+'
                classes:
                  TRANSACTION:
                    - name: t
                      all: ['{{amt}} debited']
                """);

        assertThat(set.byClass().get(MessageClass.TRANSACTION).getFirst().matches("rs 50 debited")).isTrue();
    }

    @Test
    void bankSpecificRulesComeFirstAndApplyOnlyToTheirSenders() {
        RuleSet bank = load("""
                senders: [axisbk]
                classes:
                  PROMO:
                    - name: bank-promo
                      all: ['special']
                """);
        RuleSet common = load("""
                classes:
                  TRANSACTION:
                    - name: t
                      all: ['special']
                """);
        RuleBasedClassifier classifier = new RuleBasedClassifier(List.of(bank, common));

        assertThat(bank.senders()).containsExactly("AXISBK");
        assertThat(classifier.classify("AX-AXISBK-S", "special").reason()).isEqualTo("PROMO:bank-promo");
        assertThat(classifier.classify("VM-SBIINB-S", "special").reason()).isEqualTo("TRANSACTION:t");
    }

    @Test
    void unknownClassFailsFast() {
        assertThatThrownBy(() -> load("classes: { SPAM: [ { name: x, all: ['a'] } ] }"))
                .hasMessageContaining("unknown class 'SPAM'");
    }

    @Test
    void unknownFragmentFailsFast() {
        assertThatThrownBy(() -> load("classes: { OTP: [ { name: x, all: ['{{nope}}'] } ] }"))
                .hasMessageContaining("unknown fragment 'nope'");
    }

    @Test
    void invalidRegexFailsFast() {
        assertThatThrownBy(() -> load("classes: { OTP: [ { name: x, all: ['(unclosed'] } ] }"))
                .hasMessageContaining("invalid regex");
    }

    @Test
    void ruleWithoutAllPatternFailsFast() {
        assertThatThrownBy(() -> load("classes: { OTP: [ { name: x, none: ['a'] } ] }"))
                .hasMessageContaining("needs at least one 'all' pattern");
    }

    @Test
    void rulesForUnknownClassAreRejected() {
        assertThatThrownBy(() -> load("classes: { UNKNOWN: [ { name: x, all: ['a'] } ] }"))
                .hasMessageContaining("fallback");
    }
}
