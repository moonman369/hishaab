package in.moonman.hishaab.classify.internal;

import in.moonman.hishaab.classify.MessageClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads and compiles the classifier rule files {@code classify/*.yml} (format documented in
 * {@code classify/common.yml}). Any malformed file fails fast at startup.
 */
final class ClassifierRules {

    static final String LOCATION = "classpath*:classify/*.yml";

    private static final Pattern FRAGMENT_REF = Pattern.compile("\\{\\{([a-z_]+)}}");

    /** A compiled rule: matches when every {@code all} pattern is found and no {@code none} pattern is. */
    record Rule(String name, List<Pattern> all, List<Pattern> none) {

        boolean matches(String text) {
            return all.stream().allMatch(p -> p.matcher(text).find())
                    && none.stream().noneMatch(p -> p.matcher(text).find());
        }
    }

    /** Rules from one file; {@code senders} empty = applies to every sender. */
    record RuleSet(String source, Set<String> senders, Map<MessageClass, List<Rule>> byClass) {

        boolean appliesTo(String senderMiddlePart) {
            return senders.isEmpty() || (senderMiddlePart != null && senders.contains(senderMiddlePart));
        }

        boolean isCommon() {
            return senders.isEmpty();
        }
    }

    private ClassifierRules() {
    }

    /** Bank-specific rule sets first, then common ones, each group in file-name order. */
    static List<RuleSet> loadFromClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            if (resources.length == 0) {
                throw new IllegalStateException("no classifier rules found at " + LOCATION);
            }
            List<RuleSet> sets = new ArrayList<>();
            Arrays.stream(resources)
                    .sorted(Comparator.comparing(Resource::getFilename))
                    .forEach(r -> sets.add(load(r)));
            sets.sort(Comparator.comparing(RuleSet::isCommon)); // stable: bank-specific (false) first
            return List.copyOf(sets);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RuleSet load(Resource resource) {
        String source = resource.getFilename();
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(resource);
        Map<String, Object> root = yaml.getObject();
        if (root == null) {
            throw invalid(source, "file is empty");
        }

        Set<String> senders = new TreeSet<>();
        for (Object s : list(root.get("senders"), source, "senders")) {
            senders.add(String.valueOf(s).toUpperCase(Locale.ROOT));
        }

        Map<String, String> fragments = new LinkedHashMap<>();
        map(root.get("fragments"), source, "fragments").forEach((k, v) -> fragments.put(k, String.valueOf(v)));

        Map<MessageClass, List<Rule>> byClass = new EnumMap<>(MessageClass.class);
        map(root.get("classes"), source, "classes").forEach((className, rules) -> {
            MessageClass messageClass;
            try {
                messageClass = MessageClass.valueOf(className.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalid(source, "unknown class '" + className + "'");
            }
            if (messageClass == MessageClass.UNKNOWN) {
                throw invalid(source, "UNKNOWN is the fallback and cannot have rules");
            }
            List<Rule> compiled = new ArrayList<>();
            for (Object ruleObject : list(rules, source, "classes." + className)) {
                compiled.add(compileRule(ruleObject, fragments, source, className));
            }
            byClass.put(messageClass, List.copyOf(compiled));
        });
        return new RuleSet(source, Set.copyOf(senders), byClass);
    }

    private static Rule compileRule(Object ruleObject, Map<String, String> fragments, String source,
                                    String className) {
        Map<String, Object> rule = map(ruleObject, source, "rule in " + className);
        Object nameValue = rule.get("name");
        if (nameValue == null) {
            throw invalid(source, "a rule in " + className + " has no name");
        }
        String name = String.valueOf(nameValue);
        String where = className + "/" + name;
        List<Pattern> all = compileAll(list(rule.get("all"), source, where + ".all"), fragments, source, where);
        if (all.isEmpty()) {
            throw invalid(source, where + " needs at least one 'all' pattern");
        }
        List<Pattern> none = compileAll(list(rule.get("none"), source, where + ".none"), fragments, source, where);
        return new Rule(name, all, none);
    }

    private static List<Pattern> compileAll(List<Object> regexes, Map<String, String> fragments, String source,
                                            String where) {
        List<Pattern> patterns = new ArrayList<>();
        for (Object regex : regexes) {
            String expanded = expand(String.valueOf(regex), fragments, source, where, 0);
            try {
                patterns.add(Pattern.compile(expanded));
            } catch (PatternSyntaxException e) {
                throw invalid(source, where + " has an invalid regex: " + e.getDescription());
            }
        }
        return List.copyOf(patterns);
    }

    private static String expand(String regex, Map<String, String> fragments, String source, String where,
                                 int depth) {
        if (depth > 5) {
            throw invalid(source, where + " has fragments nested too deeply (cycle?)");
        }
        Matcher m = FRAGMENT_REF.matcher(regex);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String fragment = fragments.get(m.group(1));
            if (fragment == null) {
                throw invalid(source, where + " uses unknown fragment '" + m.group(1) + "'");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(expand(fragment, fragments, source, where, depth + 1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String source, String what) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> m)) {
            throw invalid(source, what + " must be a mapping");
        }
        return (Map<String, Object>) m;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String source, String what) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> l)) {
            throw invalid(source, what + " must be a list");
        }
        return (List<Object>) l;
    }

    private static IllegalStateException invalid(String source, String why) {
        return new IllegalStateException("classifier rules " + source + ": " + why);
    }
}
