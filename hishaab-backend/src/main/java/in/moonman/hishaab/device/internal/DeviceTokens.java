package in.moonman.hishaab.device.internal;

import in.moonman.hishaab.shared.Ids;
import java.security.SecureRandom;
import java.util.Base64;

/** R-010-1: token = {@code hdt_} + base64url(32 random bytes); stored as SHA-256 hex only. */
final class DeviceTokens {

    static final String PREFIX = "hdt_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        return Ids.sha256Hex(token);
    }
}
