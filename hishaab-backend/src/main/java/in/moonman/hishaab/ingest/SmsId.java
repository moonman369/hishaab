package in.moonman.hishaab.ingest;

import in.moonman.hishaab.shared.Ids;

/**
 * The SMS message ID shared with the Android worker (SPEC §4.1):
 * {@code lowercase_hex(SHA-256(UTF8(sender + "\n" + body + "\n" + receivedAtEpochMillis)))}.
 * Inputs are used exactly as given: no trimming, case or newline changes.
 */
public final class SmsId {

    private SmsId() {
    }

    public static String of(String sender, String body, long receivedAtEpochMillis) {
        return Ids.sha256Hex(sender + "\n" + body + "\n" + receivedAtEpochMillis);
    }
}
