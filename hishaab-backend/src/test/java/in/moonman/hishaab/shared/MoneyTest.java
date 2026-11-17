package in.moonman.hishaab.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** R-000-2 / AC-000-3. */
class MoneyTest {

    @Test
    void AC_000_03_indianGroupingWithPaise() {
        assertThat(Money.parseInr("₹1,23,456.78").paise()).isEqualTo(12_345_678L);
    }

    @Test
    void AC_000_03_rsPrefixWholeRupees() {
        assertThat(Money.parseInr("Rs.50").paise()).isEqualTo(5_000L);
    }

    @Test
    void AC_000_03_threeDecimalsThrows() {
        assertThatThrownBy(() -> Money.parseInr("12.345")).isInstanceOf(Money.InvalidMoneyException.class);
    }

    @ParameterizedTest(name = "{0} -> {1} paise")
    @CsvSource(delimiter = '|', value = {
            "Rs.1,234.50   | 123450",
            "Rs 1234       | 123400",
            "INR 1,234.5   | 123450",
            "₹1,23,456.00  | 12345600",
            "1234          | 123400",
            "rs. 99.9      | 9990",
            "INR1234       | 123400",
            "0.05          | 5",
            "1,000,000.00  | 100000000",
    })
    void R_000_2_acceptedFormats(String text, long paise) {
        assertThat(Money.parseInr(text)).isEqualTo(Money.ofPaise(paise));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-50", "Rs.-50", "-Rs.50", "₹-1,234.00", "12.345", "Rs.1.999", "abc", "Rs.", "1,",
            ",100", "1,,000", "12.", ".50", "1 234", "$50", "99999999999999999999"})
    void R_000_2_rejectedFormats(String text) {
        assertThatThrownBy(() -> Money.parseInr(text)).isInstanceOf(Money.InvalidMoneyException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void R_000_2_emptyRejected(String text) {
        assertThatThrownBy(() -> Money.parseInr(text)).isInstanceOf(Money.InvalidMoneyException.class);
    }

    @Test
    void arithmeticIsExact() {
        assertThat(Money.ofPaise(150).plus(Money.ofPaise(50))).isEqualTo(Money.ofPaise(200));
        assertThat(Money.ofPaise(150).minus(Money.ofPaise(200))).isEqualTo(Money.ofPaise(-50));
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
    }
}
