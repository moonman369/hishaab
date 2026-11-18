package in.moonman.hishaab.parse.internal;

import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import in.moonman.hishaab.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** SPEC-014: turns raw template captures into clean typed fields. Pure functions. */
final class Normaliser {

    /** R-014-2. */
    private static final Map<String, Direction> DIRECTION_WORDS = Map.ofEntries(
            Map.entry("debited", Direction.DR), Map.entry("debit", Direction.DR), Map.entry("dr", Direction.DR),
            Map.entry("spent", Direction.DR), Map.entry("withdrawn", Direction.DR), Map.entry("sent", Direction.DR),
            Map.entry("paid", Direction.DR), Map.entry("purchase", Direction.DR),
            Map.entry("credited", Direction.CR), Map.entry("credit", Direction.CR), Map.entry("cr", Direction.CR),
            Map.entry("received", Direction.CR), Map.entry("deposited", Direction.CR),
            Map.entry("refund", Direction.CR), Map.entry("refunded", Direction.CR));

    /** R-014-8, checked in this order on the whole message when no channel is captured or fixed. */
    private static final Pattern UPI = Pattern.compile("UPI", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEFT = Pattern.compile("\\bNEFT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPS = Pattern.compile("\\bIMPS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RTGS = Pattern.compile("\\bRTGS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATM = Pattern.compile("\\bATM\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD = Pattern.compile("CARD|\\bPOS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOPAY = Pattern.compile("\\b(?:NACH|ECS)\\b|MANDATE|AUTO ?PAY", Pattern.CASE_INSENSITIVE);

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");
    private static final Pattern TRAILING_REF = Pattern.compile("\\s*\\b(?:ref(?:\\s*no)?|utr|rrn)\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[\\s.,;:\\-/]+$");
    private static final Pattern FOUR_DIGITS = Pattern.compile("\\d{4}");

    private Normaliser() {
    }

    /** R-014-1. */
    static long paise(String amountText) {
        try {
            return Money.parseInr(amountText).paise();
        } catch (Money.InvalidMoneyException e) {
            throw new NormalisationException(e.getMessage());
        }
    }

    /** R-014-2: unknown word → the parse fails. */
    static Direction direction(String word) {
        Direction d = DIRECTION_WORDS.get(word.strip().toLowerCase(Locale.ROOT));
        if (d == null) {
            throw new NormalisationException("unknown direction word '" + word + "'");
        }
        return d;
    }

    /** R-014-5: digits only; 6–22 digits, else null. */
    static String ref(String text) {
        if (text == null) {
            return null;
        }
        String digits = NON_DIGITS.matcher(text).replaceAll("");
        return digits.length() >= 6 && digits.length() <= 22 ? digits : null;
    }

    /** R-014-6: trim, collapse spaces, strip trailing ref fragments and punctuation; original case kept. */
    static String counterparty(String text) {
        if (text == null) {
            return null;
        }
        String cp = SPACES.matcher(text).replaceAll(" ").strip();
        cp = TRAILING_REF.matcher(cp).replaceAll("");
        cp = TRAILING_PUNCT.matcher(cp).replaceAll("");
        return cp.isEmpty() ? null : cp;
    }

    /** R-014-7. */
    static String last4(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return FOUR_DIGITS.matcher(t).matches() ? t : null;
    }

    /** R-014-8: captured/fixed channel wins; otherwise inferred from the message text. */
    static Channel channel(String captured, String body) {
        if (captured != null && !captured.isBlank()) {
            Channel c = inferChannel(captured);
            if (c != Channel.OTHER) {
                return c;
            }
            try {
                return Channel.valueOf(captured.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to inference from the body
            }
        }
        return inferChannel(body);
    }

    static Channel inferChannel(String text) {
        if (text == null) {
            return Channel.OTHER;
        }
        if (UPI.matcher(text).find()) {
            return Channel.UPI;
        }
        if (NEFT.matcher(text).find()) {
            return Channel.NEFT;
        }
        if (IMPS.matcher(text).find()) {
            return Channel.IMPS;
        }
        if (RTGS.matcher(text).find()) {
            return Channel.RTGS;
        }
        if (ATM.matcher(text).find()) {
            return Channel.ATM;
        }
        if (CARD.matcher(text).find()) {
            return Channel.CARD;
        }
        if (AUTOPAY.matcher(text).find()) {
            return Channel.AUTOPAY;
        }
        return Channel.OTHER;
    }

    /**
     * R-014-4: date+time from the text if present; date only → that date at the received time-of-day, unless
     * that is after {@code receivedAt}, then 23:59:59 of that date; nothing → {@code receivedAt}.
     */
    static Instant txnTime(String date, String time, String dateTime, Instant receivedAt) {
        if (dateTime != null) {
            return TxnDates.parseDateTime(dateTime).atZone(TxnDates.IST).toInstant();
        }
        if (date == null) {
            return receivedAt;
        }
        LocalDate day = TxnDates.parseDate(date);
        if (time != null) {
            return day.atTime(TxnDates.parseTime(time)).atZone(TxnDates.IST).toInstant();
        }
        LocalTime receivedTimeOfDay = receivedAt.atZone(TxnDates.IST).toLocalTime();
        Instant candidate = LocalDateTime.of(day, receivedTimeOfDay).atZone(TxnDates.IST).toInstant();
        if (candidate.isAfter(receivedAt)) {
            return day.atTime(23, 59, 59).atZone(TxnDates.IST).toInstant();
        }
        return candidate;
    }
}
