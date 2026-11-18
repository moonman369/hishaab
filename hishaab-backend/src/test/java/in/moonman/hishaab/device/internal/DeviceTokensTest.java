package in.moonman.hishaab.device.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** R-010-1. */
class DeviceTokensTest {

    @Test
    void R_010_1_tokenIsPrefixedBase64UrlOf32Bytes() {
        String token = DeviceTokens.newToken();

        assertThat(token).startsWith("hdt_");
        assertThat(Base64.getUrlDecoder().decode(token.substring(4))).hasSize(32);
    }

    @Test
    void R_010_1_tokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(DeviceTokens.newToken());
        }
        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void R_010_1_hashIsSha256HexAndNotTheToken() {
        String token = DeviceTokens.newToken();

        assertThat(DeviceTokens.hash(token)).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(DeviceTokens.hash(token)).isEqualTo(DeviceTokens.hash(token));
    }
}
