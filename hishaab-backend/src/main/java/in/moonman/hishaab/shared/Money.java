package in.moonman.hishaab.shared;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An amount of Indian rupees held as whole paise (H-1). Never use floating point for money.
 */
public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    /**
     * Optional currency prefix ({@code ₹}, {@code Rs}, {@code Rs.}, {@code INR}), optional whitespace,
     * integer part with optional comma grouping, optional 1–2 decimal digits.
     */
    private static final Pattern INR = Pattern.compile(
            "^(?:₹|RS\\.?|INR)?\\s*(\\d+(?:,\\d+)*)(?:\\.(\\d{1,2}))?$");

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    /**
     * Parses an INR amount as it appears in bank messages, e.g. {@code Rs.1,234.50}, {@code Rs 1234},
     * {@code INR 1,234.5}, {@code ₹1,23,456.00}, {@code 1234}.
     *
     * @throws InvalidMoneyException for null/blank input, negatives, more than two decimals,
     *                               malformed grouping or values that overflow a {@code long} of paise
     */
    public static Money parseInr(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidMoneyException("amount is empty");
        }
        Matcher m = INR.matcher(text.strip().toUpperCase(Locale.ROOT));
        if (!m.matches()) {
            throw new InvalidMoneyException("not a valid INR amount: '" + text + "'");
        }
        String rupees = m.group(1).replace(",", "");
        String fraction = m.group(2) == null ? "0" : m.group(2);
        long fractionPaise = fraction.length() == 1 ? Long.parseLong(fraction) * 10 : Long.parseLong(fraction);
        try {
            return new Money(Math.addExact(Math.multiplyExact(Long.parseLong(rupees), 100L), fractionPaise));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidMoneyException("amount out of range: '" + text + "'");
        }
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }

    /** Thrown when text cannot be read as a non-negative INR amount. */
    public static final class InvalidMoneyException extends IllegalArgumentException {
        public InvalidMoneyException(String message) {
            super(message);
        }
    }
}
