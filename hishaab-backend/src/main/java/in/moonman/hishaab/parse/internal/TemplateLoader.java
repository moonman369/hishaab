package in.moonman.hishaab.parse.internal;

import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.parse.ParsedMessage.RecordType;
import in.moonman.hishaab.shared.Source;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads {@code parse-templates/*.yml} (files in name order, templates in file order — R-013-2). Each file has a
 * top-level {@code templates:} list. Anything malformed fails fast at startup.
 */
final class TemplateLoader {

    static final String LOCATION = "classpath*:parse-templates/*.yml";

    private TemplateLoader() {
    }

    static List<ParseTemplate> loadFromClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            if (resources.length == 0) {
                throw new IllegalStateException("no parse templates found at " + LOCATION);
            }
            List<ParseTemplate> all = new ArrayList<>();
            Arrays.stream(resources).sorted(Comparator.comparing(Resource::getFilename))
                    .forEach(r -> all.addAll(load(r)));
            Set<String> ids = new HashSet<>();
            for (ParseTemplate t : all) {
                if (!ids.add(t.id())) {
                    throw new IllegalStateException("parse template id '" + t.id() + "' is defined twice");
                }
            }
            return List.copyOf(all);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<ParseTemplate> load(Resource resource) {
        String file = resource.getFilename();
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(resource);
        Map<String, Object> root = yaml.getObject();
        if (root == null || !(root.get("templates") instanceof List<?> list)) {
            throw invalid(file, "?", "needs a top-level 'templates:' list");
        }
        List<ParseTemplate> templates = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw invalid(file, "?", "each template must be a mapping");
            }
            templates.add(compile(file, map));
        }
        return templates;
    }

    private static ParseTemplate compile(String file, Map<?, ?> t) {
        String id = text(t, "id", file, "?");
        int version;
        try {
            version = Integer.parseInt(text(t, "version", file, id));
        } catch (NumberFormatException e) {
            throw invalid(file, id, "version must be a whole number");
        }
        if (version < 1) {
            throw invalid(file, id, "version must be >= 1");
        }
        Source source = enumValue(Source.class, text(t, "source", file, id), file, id, "source");
        RecordType recordType = enumValue(RecordType.class, text(t, "recordType", file, id), file, id, "recordType");
        Bank bank = t.get("bank") == null ? null : enumValue(Bank.class, String.valueOf(t.get("bank")), file, id, "bank");
        AccountType accountType = t.get("accountType") == null ? null
                : enumValue(AccountType.class, String.valueOf(t.get("accountType")), file, id, "accountType");
        if (recordType == RecordType.TRANSACTION && (bank == null || accountType == null)) {
            throw invalid(file, id, "TRANSACTION templates need 'bank' and 'accountType'");
        }
        String senderPatternText = text(t, "senderPattern", file, id);
        String regexText = text(t, "regex", file, id);

        Map<String, String> fixed = new LinkedHashMap<>();
        if (t.get("fixed") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> {
                if (!ParseTemplate.FIXABLE.contains(String.valueOf(k))) {
                    throw invalid(file, id, "'fixed' may only set " + ParseTemplate.FIXABLE + ", not '" + k + "'");
                }
                fixed.put(String.valueOf(k), String.valueOf(v));
            });
        } else if (t.get("fixed") != null) {
            throw invalid(file, id, "'fixed' must be a mapping");
        }

        Pattern senderPattern;
        Pattern regex;
        try {
            senderPattern = Pattern.compile(senderPatternText, Pattern.CASE_INSENSITIVE);
            regex = Pattern.compile(regexText, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw invalid(file, id, "invalid regex: " + e.getDescription());
        }
        Set<String> groups = Set.copyOf(regex.namedGroups().keySet());
        if (!groups.contains("amount")) {
            throw invalid(file, id, "regex needs an (?<amount>...) group (R-013-3)");
        }
        if (!groups.contains("dir") && !fixed.containsKey("direction")) {
            throw invalid(file, id, "needs a (?<dir>...) group or fixed.direction (R-013-3)");
        }
        return new ParseTemplate(id, version, bank, source, senderPatternText, regexText, Map.copyOf(fixed),
                accountType, recordType, senderPattern, regex, groups);
    }

    private static String text(Map<?, ?> t, String key, String file, String id) {
        Object v = t.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw invalid(file, id, "missing '" + key + "'");
        }
        return String.valueOf(v);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String file, String id, String key) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(file, id, "unknown " + key + " '" + value + "'");
        }
    }

    private static IllegalStateException invalid(String file, String id, String why) {
        return new IllegalStateException("parse template " + file + "#" + id + ": " + why);
    }
}
