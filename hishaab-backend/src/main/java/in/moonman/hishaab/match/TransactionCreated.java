package in.moonman.hishaab.match;

import java.util.UUID;

/**
 * Published inside the match transaction after a new transaction is inserted (R-031-6).
 */
public record TransactionCreated(UUID transactionId, UUID initialParsedRecordId) {
}
