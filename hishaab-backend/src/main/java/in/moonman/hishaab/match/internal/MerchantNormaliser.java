package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.MerchantService.MerchantType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalisation and classification of counterparty strings (R-030-2, R-030-3).
 */
final class MerchantNormaliser {

    private static final Pattern ASTERISKS = Pattern.compile("\\*+");
    private static final Pattern DIGIT_RUNS = Pattern.compile("\\b\\d{4,}\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<String> COMMON_SUFFIXES = List.of(
            "PRIVATE LIMITED",
            "PVT LTD",
            "PVT. LTD.",
            "PVT. LTD",
            "PVT LTD.",
            "LIMITED",
            "LTD.",
            "LTD",
            "INDIA",
            "IND",
            "INC.",
            "INC",
            "CORP.",
            "CORP",
            "LLP."
    );

    private static final Set<String> CITY_CODES = Set.of(
            "BLR", "BGLR", "BLRU", "BOM", "MUM", "DEL", "NDLS",
            "HYD", "MAA", "CHE", "CCU", "KOL", "PNQ", "AMD", "GOI", "COK", "JAI"
    );

    private static final Set<String> BUSINESS_KEYWORDS = Set.of(
            "PVT", "LTD", "LIMITED", "PRIVATE", "STORE", "STORES", "MART", "BAZAAR", "RETAIL",
            "SHOP", "SHOPS", "HOTEL", "HOTELS", "RESTAURANT", "RESTAURANTS", "CAFE", "COFFEE",
            "FOODS", "FOOD", "TRADERS", "TRADING", "AGENCY", "AGENCIES", "TRAVELS", "TRAVEL",
            "PHARMA", "PHARMACY", "HOSPITAL", "CLINIC", "PAY", "BILLS", "BILL", "RECHARGE",
            "TELECOM", "TECHNOLOGIES", "TECH", "SERVICES", "SOLUTIONS", "ENTERPRISE",
            "ENTERPRISES", "CORP", "INC", "LLP", "PETROL", "PUMP", "FUEL", "STATION",
            "BANK", "AMC", "FUND", "MUTUAL", "INSURANCE", "LIFE", "EXPRESS", "LOGISTICS",
            "SUPERMARKET", "HYPERMARKET", "MARKET", "SYSTEMS", "MEDIA", "ONLINE", "INDIA",
            "COMMERCE", "FRESH", "BAKERY", "SWEETS", "CLUB", "FITNESS", "GYM", "LAB", "LABS"
    );

    private static final Pattern UPI_HANDLE_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+$");

    private MerchantNormaliser() {
    }

    /**
     * R-030-2: uppercase, remove *, digits runs >= 4, and common suffixes (PVT LTD, LIMITED, INDIA, BLR, city codes list), collapse spaces.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toUpperCase(Locale.ROOT);
        s = ASTERISKS.matcher(s).replaceAll(" ");
        s = DIGIT_RUNS.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();

        // Repeatedly strip suffixes from the end of the string
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : COMMON_SUFFIXES) {
                if (s.endsWith(" " + suffix)) {
                    s = s.substring(0, s.length() - suffix.length() - 1).trim();
                    changed = true;
                } else if (s.equals(suffix)) {
                    s = "";
                    changed = true;
                }
            }
            for (String city : CITY_CODES) {
                if (s.endsWith(" " + city)) {
                    s = s.substring(0, s.length() - city.length() - 1).trim();
                    changed = true;
                } else if (s.equals(city)) {
                    s = "";
                    changed = true;
                }
            }
        }

        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * R-030-3: PERSON if the raw string looks like a personal UPI ID or a name of 2–3 words with no business keywords; else MERCHANT.
     */
    static MerchantType classifyType(String raw) {
        if (raw == null || raw.isBlank()) {
            return MerchantType.MERCHANT;
        }
        String trimmed = raw.trim();

        if (UPI_HANDLE_PATTERN.matcher(trimmed).matches()) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            String[] parts = upper.split("@", 2);
            String userPart = parts[0];
            // Check if user part contains any business keyword
            String[] tokens = userPart.split("[._-]");
            for (String token : tokens) {
                if (BUSINESS_KEYWORDS.contains(token)) {
                    return MerchantType.MERCHANT;
                }
            }
            return MerchantType.PERSON;
        }

        // Clean to uppercase words
        String upper = trimmed.toUpperCase(Locale.ROOT);
        String[] words = WHITESPACE.split(upper);
        if (words.length >= 2 && words.length <= 3) {
            boolean allAlphaWords = true;
            for (String w : words) {
                // Must be alphabetic only
                if (!w.matches("^[A-Z.]+$")) {
                    allAlphaWords = false;
                    break;
                }
                String cleanWord = w.replace(".", "");
                if (BUSINESS_KEYWORDS.contains(cleanWord)) {
                    return MerchantType.MERCHANT;
                }
            }
            if (allAlphaWords) {
                return MerchantType.PERSON;
            }
        }

        return MerchantType.MERCHANT;
    }
}
