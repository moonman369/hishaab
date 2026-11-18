package in.moonman.hishaab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** HLD §5.7: allowlist matching uses the stable middle part of the sender header. */
class SenderIdsTest {

    @ParameterizedTest
    @CsvSource({
            "AX-AXISBK-S, AXISBK",
            "VM-SBIINB, SBIINB",
            "JD-SBICRD-T, SBICRD",
            "ax-axisbk-s, AXISBK",
            "AXISBK, AXISBK",
            "BZ-VMPROMOS-P, VMPROMOS",
    })
    void middlePartExtracted(String sender, String expected) {
        assertThat(SenderIds.middlePart(sender)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+919876543210", "9876543210", "AX-AXIS1K-S", "AX-AB-S", "", "AX--S", "X-AXISBK-S-EXTRA"})
    void nonHeadersYieldNothing(String sender) {
        assertThat(SenderIds.middlePart(sender)).isEmpty();
    }
}
