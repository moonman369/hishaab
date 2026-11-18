package in.moonman.hishaab.classify.internal;

import in.moonman.hishaab.classify.Classification;
import in.moonman.hishaab.classify.ClassifyService;
import in.moonman.hishaab.classify.MessageClass;
import in.moonman.hishaab.classify.internal.ClassifierRules.Rule;
import in.moonman.hishaab.classify.internal.ClassifierRules.RuleSet;
import in.moonman.hishaab.shared.SenderIds;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * SPEC-012 rule engine. Classes are checked in {@link MessageClass#CHECK_ORDER}; within a class, bank-specific
 * rules come before common ones. First match wins; nothing matching → {@code UNKNOWN}. Pure: no DB, no state.
 */
@Service
class RuleBasedClassifier implements ClassifyService {

    static final String NO_MATCH_REASON = "no-rule-matched";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final List<RuleSet> ruleSets;

    RuleBasedClassifier() {
        this(ClassifierRules.loadFromClasspath());
    }

    RuleBasedClassifier(List<RuleSet> ruleSets) {
        this.ruleSets = List.copyOf(ruleSets);
    }

    @Override
    public Classification classify(String sender, String body) {
        String text = normalise(body);
        String senderPart = SenderIds.middlePart(sender).orElse(null);
        List<RuleSet> applicable = ruleSets.stream().filter(s -> s.appliesTo(senderPart)).toList();

        for (MessageClass messageClass : MessageClass.CHECK_ORDER) {
            for (RuleSet set : applicable) {
                for (Rule rule : set.byClass().getOrDefault(messageClass, List.of())) {
                    if (rule.matches(text)) {
                        return new Classification(messageClass, messageClass.name() + ":" + rule.name());
                    }
                }
            }
        }
        return new Classification(MessageClass.UNKNOWN, NO_MATCH_REASON);
    }

    @Override
    public boolean mentionsAmount(String body) {
        String text = normalise(body);
        return ruleSets.stream()
                .map(RuleSet::amount)
                .filter(java.util.Objects::nonNull)
                .anyMatch(p -> p.matcher(text).find());
    }

    static String normalise(String body) {
        return body == null ? "" : WHITESPACE.matcher(body.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
