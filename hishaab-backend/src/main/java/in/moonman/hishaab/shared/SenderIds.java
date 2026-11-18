package in.moonman.hishaab.shared;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the stable middle part of an Indian commercial SMS header (HLD §5.7):
 * {@code AX-AXISBK-S} → {@code AXISBK}, {@code VM-SBIINB} → {@code SBIINB}, bare {@code AXISBK} → {@code AXISBK}.
 * Phone numbers and anything else yield empty, so they can never match the allowlist.
 */
public final class SenderIds {

    private static final Pattern HEADER = Pattern.compile("^(?:[A-Z0-9]{2}-)?([A-Z]{3,8})(?:-[A-Z])?$");

    private SenderIds() {
    }

    public static Optional<String> middlePart(String sender) {
        if (sender == null) {
            return Optional.empty();
        }
        Matcher m = HEADER.matcher(sender.strip().toUpperCase(Locale.ROOT));
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
