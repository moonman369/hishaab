package in.moonman.hishaab.parse.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R-014-3: dates and times as banks write them. Month names are case-insensitive English; two-digit years
 * are 20yy. Everything is interpreted in {@link #IST}.
 */
final class TxnDates {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            date("dd-MM-uuuu"), date2("dd-MM-"),
            date("dd/MM/uuuu"), date2("dd/MM/"),
            date("dd-MMM-uuuu"), date2("dd-MMM-"),
            date("ddMMMuuuu"), date2("ddMMM"), date("dMMMuuuu"), date2("dMMM"),
            date("d MMM uuuu"), date("d-MMM-uuuu"),
            date("MMM d, uuuu"), date("MMM d uuuu"));

    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendPattern("H:mm").optionalStart().appendPattern(":ss").optionalEnd()
            .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);

    /** A trailing time in a combined date-time string, e.g. "Oct 07, 2026, 18:43". */
    private static final Pattern TRAILING_TIME = Pattern.compile("^(.*?)[\\s,]+(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*(?:IST|hrs)?$",
            Pattern.CASE_INSENSITIVE);

    private TxnDates() {
    }

    static LocalDate parseDate(String text) {
        String t = text.strip();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new NormalisationException("unrecognised date '" + text + "'");
    }

    static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text.strip(), TIME);
        } catch (DateTimeParseException e) {
            throw new NormalisationException("unrecognised time '" + text + "'");
        }
    }

    /** Date and time in one capture, separated by spaces and/or a comma. */
    static java.time.LocalDateTime parseDateTime(String text) {
        Matcher m = TRAILING_TIME.matcher(text.strip());
        if (!m.matches()) {
            throw new NormalisationException("unrecognised date-time '" + text + "'");
        }
        return parseDate(m.group(1)).atTime(parseTime(m.group(2)));
    }

    private static DateTimeFormatter date(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** {@code prefix} followed by a two-digit year read as 20yy. */
    private static DateTimeFormatter date2(String prefix) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(prefix)
                .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }
}
