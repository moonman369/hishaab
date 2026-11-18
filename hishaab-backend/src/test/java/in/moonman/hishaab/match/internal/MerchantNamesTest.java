package in.moonman.hishaab.match.internal;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.match.MerchantService.MerchantType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** R-030-2 / R-030-3. Names are synthetic. */
class MerchantNamesTest {

    private static MerchantNames.Normalised of(String raw) {
        return MerchantNames.of(raw).orElseThrow();
    }

    @Test
    void AC_030_01_swiggyVariantsShareOneKey() {
        assertThat(of("SWIGGY*BLR").key()).isEqualTo("SWIGGY");
        assertThat(of("Swiggy Ltd").key()).isEqualTo("SWIGGY");
        assertThat(of("Swiggy Ltd").displayName()).isEqualTo("Swiggy");
    }

    @Test
    void AC_030_02_personalUpiIdIsAPerson() {
        MerchantNames.Normalised n = of("rahul.k@okaxis");

        assertThat(n.type()).isEqualTo(MerchantType.PERSON);
        assertThat(n.key()).isEqualTo("rahul.k@okaxis");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "WWW SHOPKART 1234567        | WWW SHOPKART",
            "Blue Bottle Cafe Pvt Ltd    | BLUE BOTTLE CAFE",
            "MR. KARAN SETHI             | KARAN SETHI",
            "Karan  Sethi                | KARAN SETHI",
            "STREAMCO* PRO SUB           | STREAMCO PRO SUB",
            "J K MOTORS.                 | J K MOTORS",
            "AMAZON1234 MUMBAI           | AMAZON",
            "Chai Point & Co             | CHAI POINT & CO",
    })
    void R_030_2_normalisation(String raw, String key) {
        assertThat(of(raw).key()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "123456", "*** ---"})
    void R_030_2_nothingUsableLeftIsEmpty(String raw) {
        assertThat(MerchantNames.of(raw)).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "ROHAN MEHTA                          | PERSON",
            "Neha  Ver                            | PERSON",
            "MR. KARAN SETHI                      | PERSON",
            "9876543210@ybl                       | PERSON",
            "priya.s99@oksbi                      | PERSON",
            "Ravi Tea Corner                      | MERCHANT",
            "BLUE BOTTLE CAFE                     | MERCHANT",
            "J K MOTORS                           | MERCHANT",
            "SWIGGY                               | MERCHANT",
            "A B C D                              | MERCHANT",
            "swiggy.stores@axb                    | MERCHANT",
            "paytmqr2810050501011abcd@paytm       | MERCHANT",
            "Play Rewards                         | MERCHANT",
            "PETROL PUMP 42                       | MERCHANT",
    })
    void R_030_3_personOrMerchantGuess(String raw, MerchantType expected) {
        assertThat(of(raw).type()).isEqualTo(expected);
    }

    @Test
    void displayNamesAreTitleCased() {
        assertThat(of("MR. KARAN SETHI").displayName()).isEqualTo("Karan Sethi");
        assertThat(of("BLUE BOTTLE CAFE").displayName()).isEqualTo("Blue Bottle Cafe");
        assertThat(of("PETROL PUMP 42").displayName()).isEqualTo("Petrol Pump 42");
    }
}
