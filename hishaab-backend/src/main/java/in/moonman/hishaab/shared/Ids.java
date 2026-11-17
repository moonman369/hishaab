package in.moonman.hishaab.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * ID helpers. New rows get time-ordered UUIDv7 (RFC 9562) so B-tree inserts stay local;
 * {@code raw_message.id} is a content hash (see SPEC §4.1).
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return uuidV7(Clock.systemUTC().millis());
    }

    /** UUIDv7: 48-bit Unix millis, version 7, 74 random bits, IETF variant. */
    static UUID uuidV7(long epochMillis) {
        long randA = RANDOM.nextLong() & 0x0FFFL;
        long msb = (epochMillis << 16) | 0x7000L | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code text}. */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
