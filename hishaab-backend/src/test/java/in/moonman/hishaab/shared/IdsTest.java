package in.moonman.hishaab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void newIdIsVersion7WithIetfVariantAndEmbedsTime() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after);
    }

    @Test
    void idsAreOrderedByTime() {
        UUID earlier = Ids.uuidV7(1_000L);
        UUID later = Ids.uuidV7(2_000L);
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void sha256HexIsLowercaseHex() {
        assertThat(Ids.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
