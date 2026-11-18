package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.MerchantService.MerchantType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * R-030-2 / R-030-3: turns a raw counterparty into a stable alias key, a display name and a guessed type.
 * Pure functions. The type is only a first guess; you can correct it (PATCH /merchants/{id}).
 */
final class MerchantNames {

    record Normalised(String key, String displayName, MerchantType type) {
    }

    private static final Pattern VPA = Pattern.compile("^([A-Za-z0-9._-]+)@([A-Za-z][A-Za-z0-9]*)$");
    private static final Pattern PHONE_LOCAL = Pattern.compile("^(?:91)?\\d{10}$");
    private static final Pattern NAME_LIKE_LOCAL = Pattern.compile("^[a-z]+(?:[._-][a-z]+)*\\d{0,4}$");
    private static final Pattern NOT_NAME_CHARS = Pattern.compile("[^A-Z0-9& ]");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern LETTERS = Pattern.compile("^[A-Z]+$");

    /** Trailing words removed from names (company forms, country, city codes). */
    private static final Set<String> SUFFIXES = Set.of(
            "PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "INC", "CORP", "INDIA", "IN",
            "BLR", "BANGALORE", "BENGALURU", "MUM", "MUMBAI", "BOM", "DEL", "DELHI", "NEW", "NCR", "GGN", "GURGAON",
            "GURUGRAM", "NOIDA", "KOL", "KOLKATA", "CCU", "CHN", "CHENNAI", "MAA", "HYD", "HYDERABAD", "PUNE", "PNQ",
            "AHMEDABAD", "AMD", "JAIPUR", "LUCKNOW");

    /** Leading honorifics, dropped so "MR. KARAN SETHI" and "Karan Sethi" are the same person. */
    private static final Set<String> TITLES = Set.of("MR", "MRS", "MS", "MISS", "DR", "SHRI", "SMT", "KUM", "SRI");

    /** Words that make a 2–3 word name a business, not a person. */
    private static final Set<String> BUSINESS_WORDS = Set.of(
            "STORE", "STORES", "SHOP", "SHOPPE", "MART", "CAFE", "TEA", "COFFEE", "CORNER", "RESTAURANT", "HOTEL",
            "FOOD", "FOODS", "KITCHEN", "BAKERY", "SWEETS", "DHABA", "MEDICAL", "MEDICALS", "PHARMA", "PHARMACY",
            "CHEMIST", "CHEMISTS", "MOTORS", "AUTO", "AUTOMOBILES", "PETROL", "FUEL", "FUELS", "PUMP", "SERVICE",
            "SERVICES", "ENTERPRISE", "ENTERPRISES", "TRADERS", "TRADING", "AGENCY", "AGENCIES", "CENTRE", "CENTER",
            "STATION", "STALL", "INDUSTRIES", "SOLUTIONS", "TECH", "TECHNOLOGIES", "COMPANY", "CO", "BANK",
            "INSURANCE", "FINANCE", "PAY", "PAYMENTS", "WALLET", "ONLINE", "RETAIL", "SUPERMARKET", "BAZAAR", "BAZAR",
            "MALL", "CINEMA", "CINEMAS", "TRAVELS", "TOURS", "CABS", "TAXI", "ELECTRONICS", "ELECTRICALS", "WORLD",
            "REWARDS", "GYM", "FITNESS", "CLINIC", "HOSPITAL", "LAB", "LABS", "DIAGNOSTICS", "SCHOOL", "COLLEGE",
            "ACADEMY", "CLUB", "MARKET", "POINT", "HOUSE", "PLAZA", "SALON", "SPA", "STUDIO", "TRUST", "FOUNDATION",
            "SOCIETY", "WWW", "COM", "SUB", "PLAN", "PRO", "PREMIUM", "RECHARGE", "BILL", "BILLS", "MOBILE", "BROADBAND",
            "FUND", "MF", "SECURITIES", "CAPITAL");

    /** VPA local parts that belong to merchant QR codes or aggregators. */
    private static final Pattern MERCHANT_VPA_LOCAL = Pattern.compile(
            "^(?:paytmqr|paytm-|bharatpe|q\\d|gpay-|mab\\.|merchant|razorpay|cashfree|payu|billdesk|ccavenue).*");

    private MerchantNames() {
    }

    static Optional<Normalised> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = SPACES.matcher(raw).replaceAll(" ").strip();

        var vpa = VPA.matcher(trimmed);
        if (vpa.matches()) {
            String address = trimmed.toLowerCase(Locale.ROOT);
            return Optional.of(new Normalised(address, address, vpaType(vpa.group(1).toLowerCase(Locale.ROOT))));
        }

        String upper = trimmed.toUpperCase(Locale.ROOT);
        upper = NOT_NAME_CHARS.matcher(upper).replaceAll(" ");
        upper = LONG_DIGITS.matcher(upper).replaceAll(" ");
        List<String> words = new ArrayList<>(Arrays.asList(SPACES.matcher(upper).replaceAll(" ").strip().split(" ")));
        words.removeIf(String::isEmpty);
        while (words.size() > 1 && TITLES.contains(words.getFirst())) {
            words.removeFirst();
        }
        while (words.size() > 1 && SUFFIXES.contains(words.getLast())) {
            words.removeLast();
        }
        if (words.isEmpty() || (words.size() == 1 && "&".equals(words.getFirst()))) {
            return Optional.empty();
        }
        String key = String.join(" ", words);
        return Optional.of(new Normalised(key, titleCase(words), nameType(words)));
    }

    private static MerchantType vpaType(String local) {
        if (MERCHANT_VPA_LOCAL.matcher(local).matches()) {
            return MerchantType.MERCHANT;
        }
        if (PHONE_LOCAL.matcher(local).matches()) {
            return MerchantType.PERSON;
        }
        if (NAME_LIKE_LOCAL.matcher(local).matches()) {
            boolean business = Arrays.stream(local.split("[._\\-\\d]+"))
                    .anyMatch(part -> BUSINESS_WORDS.contains(part.toUpperCase(Locale.ROOT)));
            return business ? MerchantType.MERCHANT : MerchantType.PERSON;
        }
        return MerchantType.MERCHANT;
    }

    /** 2–3 plain words, none of them business words → a person; everything else → a merchant. */
    private static MerchantType nameType(List<String> words) {
        boolean personLike = words.size() >= 2 && words.size() <= 3
                && words.stream().allMatch(w -> LETTERS.matcher(w).matches())
                && words.stream().noneMatch(BUSINESS_WORDS::contains);
        return personLike ? MerchantType.PERSON : MerchantType.MERCHANT;
    }

    private static String titleCase(List<String> words) {
        return words.stream()
                .map(w -> w.length() <= 1 || !LETTERS.matcher(w).matches() ? w
                        : w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }
}
