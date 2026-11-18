package in.moonman.hishaab.match;

import java.util.UUID;

/**
 * Published inside the match transaction after evidence is attached or fields updated on a transaction (R-031-6).
 */
public record TransactionUpdated(UUID transactionId, UUID attachedParsedRecordId, MatchMethod matchMethod) {

    public enum MatchMethod { FIRST, REF, WINDOW, MANUAL }
}
