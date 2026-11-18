package in.moonman.hishaab.parse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC-014. */
class NormaliserTest {

    private static Instant ist(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+05:30").toInstant();
    }

    // --- R-014-3 / AC-014-1 --------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "17-11-26        | 2026-11-17",
            "17-11-2026      | 2026-11-17",
            "17/11/26        | 2026-11-17",
            "17/11/2026      | 2026-11-17",
            "17Nov26         | 2026-11-17",
            "5Nov26          | 2026-11-05",
            "17-Nov-26       | 2026-11-17",
            "05-Nov-2026     | 2026-11-05",
            "05-NOV-2026     | 2026-11-05",
            "05-oct-2026     | 2026-10-05",
            "17 Nov 2026     | 2026-11-17",
            "'Oct 07, 2026'  | 2026-10-07",
            "'Nov 9, 2026'   | 2026-11-09",
            "19Aug25         | 2025-08-19",
    })
    void AC_014_01_dateFormats(String text, String expected) {
        assertThat(TxnDates.parseDate(text)).isEqualTo(LocalDate.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"32-11-26", "17-13-2026", "2026-11-17", "Nov", "17.11.2026", ""})
    void AC_014_01_badDatesFail(String text) {
        assertThatThrownBy(() -> TxnDates.parseDate(text)).isInstanceOf(NormalisationException.class);
    }

    @Test
    void AC_014_01_timesWithAndWithoutSeconds() {
        Instant received = ist("2026-11-17T23:00:00");
        assertThat(Normaliser.txnTime("17-11-26", "14:14:51", null, received)).isEqualTo(ist("2026-11-17T14:14:51"));
        assertThat(Normaliser.txnTime("17-11-26", "9:05", null, received)).isEqualTo(ist("2026-11-17T09:05:00"));
        assertThat(Normaliser.txnTime(null, null, "Oct 07, 2026, 18:43", received)).isEqualTo(ist("2026-10-07T18:43:00"));
    }

    // --- R-014-4 / AC-014-2 --------------------------------------------------------------------------------

    @Test
    void AC_014_02_dateOnlyTodayUsesReceivedTimeOfDay() {
        Instant received = ist("2026-11-17T21:40:00");

        assertThat(Normaliser.txnTime("17-11-26", null, null, received)).isEqualTo(ist("2026-11-17T21:40:00"));
    }

    @Test
    void R_014_4_dateOnlyInTheFutureUsesEndOfThatDay() {
        Instant received = ist("2026-11-05T23:50:00");

        assertThat(Normaliser.txnTime("06-11-26", null, null, received)).isEqualTo(ist("2026-11-06T23:59:59"));
    }

    @Test
    void R_014_4_noDateUsesReceivedAt() {
        Instant received = ist("2026-11-05T10:00:00");

        assertThat(Normaliser.txnTime(null, null, null, received)).isEqualTo(received);
    }

    // --- R-014-5 / AC-014-3 --------------------------------------------------------------------------------

    @Test
    void AC_014_03_refKeepsDigitsOnly() {
        assertThat(Normaliser.ref("UPI Ref No 4321 1234 5678")).isEqualTo("432112345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "ACH-CR-SAL-ACME", "12345678901234567890123"})
    void R_014_5_refOutsideSixToTwentyTwoDigitsIsNull(String text) {
        assertThat(Normaliser.ref(text)).isNull();
    }

    // --- R-014-1 / 2 / 6 / 7 / 8 ---------------------------------------------------------------------------

    @Test
    void R_014_1_amountsToPaise() {
        assertThat(Normaliser.paise("1,23,456.78")).isEqualTo(12_345_678L);
        assertThat(Normaliser.paise("28.0")).isEqualTo(2_800L);
        assertThatThrownBy(() -> Normaliser.paise("12.345")).isInstanceOf(NormalisationException.class);
    }

    @ParameterizedTest
    @CsvSource({"debited,DR", "Debited,DR", "spent,DR", "withdrawn,DR", "sent,DR", "paid,DR", "purchase,DR", "DR,DR",
            "credited,CR", "received,CR", "deposited,CR", "refund,CR", "CR,CR"})
    void R_014_2_directionWords(String word, Direction expected) {
        assertThat(Normaliser.direction(word)).isEqualTo(expected);
    }

    @Test
    void R_014_2_unknownDirectionWordFails() {
        assertThatThrownBy(() -> Normaliser.direction("booked")).isInstanceOf(NormalisationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'  J K   MOTORS. '      | J K MOTORS",
            "'Neha  Ver'             | Neha Ver",
            "'SWIGGY Ref No 1234567' | SWIGGY",
            "'Metro Conn.'           | Metro Conn",
            "'Reflex Gym'            | Reflex Gym",
            "'STREAMCO* PRO SUB'     | STREAMCO* PRO SUB",
    })
    void R_014_6_counterpartyCleanup(String raw, String expected) {
        assertThat(Normaliser.counterparty(raw)).isEqualTo(expected);
    }

    @Test
    void R_014_7_last4MustBeExactlyFourDigits() {
        assertThat(Normaliser.last4("7311")).isEqualTo("7311");
        assertThat(Normaliser.last4("731")).isNull();
        assertThat(Normaliser.last4("73112")).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "UPI/P2M/1234/SHOP SMS BLOCKUPI | UPI",
            "Ref: NEFT-HDFC000123           | NEFT",
            "Ref: IMPS-123                  | IMPS",
            "via RTGS                       | RTGS",
            "Channel ATM                    | ATM",
            "SMS BLOCKCARD XX1234           | CARD",
            "POS purchase                   | CARD",
            "NACH debit for mandate         | AUTOPAY",
            "TREATMENT charges              | OTHER",
            "transfer from someone          | OTHER",
    })
    void R_014_8_channelInference(String text, Channel expected) {
        assertThat(Normaliser.inferChannel(text)).isEqualTo(expected);
    }

    @Test
    void R_014_8_capturedOrFixedChannelWins() {
        assertThat(Normaliser.channel("ATM", "UPI everywhere")).isEqualTo(Channel.ATM);
        assertThat(Normaliser.channel("OTHER", "UPI everywhere")).isEqualTo(Channel.OTHER);
        assertThat(Normaliser.channel(null, "UPI everywhere")).isEqualTo(Channel.UPI);
    }
}
