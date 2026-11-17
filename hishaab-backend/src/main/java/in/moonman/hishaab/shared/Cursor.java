package in.moonman.hishaab.shared;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * Opaque paging cursor: base64url of {@code (sortKey, id)} (SPEC §2.4).
 * Clients must treat the encoded form as opaque.
 */
public record Cursor(String sortKey, String id) {

    private static final char SEP = '\n';

    public Cursor {
        if (sortKey == null || id == null || sortKey.indexOf(SEP) >= 0 || id.indexOf(SEP) >= 0) {
            throw new IllegalArgumentException("cursor parts must be non-null and contain no newline");
        }
    }

    public String encode() {
        byte[] raw = (sortKey + SEP + id).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @throws InvalidCursorException (400 {@code INVALID_CURSOR}) if the text is not a cursor we issued */
    public static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int sep = raw.indexOf(SEP);
            if (sep < 0 || raw.indexOf(SEP, sep + 1) >= 0) {
                throw new InvalidCursorException();
            }
            return new Cursor(raw.substring(0, sep), raw.substring(sep + 1));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidCursorException();
        }
    }

    public static final class InvalidCursorException extends ApiException {
        public InvalidCursorException() {
            super(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor is malformed");
        }
    }
}
