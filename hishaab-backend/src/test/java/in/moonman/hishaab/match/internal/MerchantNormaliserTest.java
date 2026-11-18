package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.MerchantService.MerchantType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantNormaliserTest {

    @Test
    void AC_030_01_swiggyBlrAndSwiggyLtdNormaliseToSameAlias() {
        String n1 = MerchantNormaliser.normalise("SWIGGY*BLR");
        String n2 = MerchantNormaliser.normalise("Swiggy Ltd");
        assertThat(n1).isEqualTo("SWIGGY");
        assertThat(n2).isEqualTo("SWIGGY");
    }

    @Test
    void AC_030_02_rahulKOkaxisIsPerson() {
        MerchantType type = MerchantNormaliser.classifyType("rahul.k@okaxis");
        assertThat(type).isEqualTo(MerchantType.PERSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "amit.sharma@okaxis",
            "priya123@okhdfcbank",
            "john.doe@icici",
            "9876543210@paytm",
            "9988776655@ybl"
    })
    void personalUpiHandlesClassifiedAsPerson(String upi) {
        assertThat(MerchantNormaliser.classifyType(upi)).isEqualTo(MerchantType.PERSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Rahul Kumar",
            "Ayan Sen",
            "Priya Ranjan Das"
    })
    void twoOrThreeWordNamesWithoutBusinessKeywordsArePerson(String name) {
        assertThat(MerchantNormaliser.classifyType(name)).isEqualTo(MerchantType.PERSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Swiggy",
            "Uber India Systems",
            "Amazon Retail India Pvt Ltd",
            "Apollo Pharmacy",
            "Starbucks Coffee",
            "Reliance Fresh Store",
            "Shell Petrol Pump"
    })
    void businessesClassifiedAsMerchant(String name) {
        assertThat(MerchantNormaliser.classifyType(name)).isEqualTo(MerchantType.MERCHANT);
    }

    @ParameterizedTest
    @CsvSource({
            "'SWIGGY*BLR', 'SWIGGY'",
            "'Swiggy Ltd', 'SWIGGY'",
            "'AMAZON PAY INDIA PVT LTD', 'AMAZON PAY'",
            "'UBER *TRIP BLR 12345', 'UBER TRIP'",
            "'ZOMATO RESTAURANT 9876', 'ZOMATO RESTAURANT'",
            "'BIGBASKET BGLR', 'BIGBASKET'",
            "'BLINKIT MUM', 'BLINKIT'",
            "'FLIPKART INDIA PRIVATE LIMITED', 'FLIPKART'"
    })
    void normaliseStripsAsterisksDigitRunsAndCommonSuffixes(String input, String expected) {
        assertThat(MerchantNormaliser.normalise(input)).isEqualTo(expected);
    }
}
