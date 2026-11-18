package in.moonman.hishaab.parse.internal;

import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.parse.ParsedMessage.RecordType;
import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.shared.Source;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One compiled template (SPEC-013). {@code senderPattern} must fully match the sender's middle part (SMS) or the
 * lowercased from-address (EMAIL); {@code regex} is case-insensitive and searched with {@code find()}.
 */
record ParseTemplate(String id, int version, Bank bank, Source source, String senderPatternText, String regexText,
                     Map<String, String> fixed, AccountType accountType, RecordType recordType,
                     Pattern senderPattern, Pattern regex, Set<String> groups) {

    /** Fields a template may set via {@code fixed:} (strings, normalised like captures). */
    static final Set<String> FIXABLE = Set.of("direction", "channel");

    /** Named groups the engine understands (SPEC-013). Others are allowed and ignored. */
    static final Set<String> KNOWN_GROUPS = Set.of("amount", "dir", "last4", "channel", "ref", "cp", "date", "time",
            "datetime", "bal");

    /**
     * AC-013-3: hash over everything that changes the parse output, so any edit without a version bump is caught.
     */
    String regexHash() {
        return Ids.sha256Hex(String.join("\u0000", source.name(), senderPatternText, regexText,
                new TreeMap<>(fixed).toString(), String.valueOf(accountType), recordType.name(), String.valueOf(bank)));
    }
}
