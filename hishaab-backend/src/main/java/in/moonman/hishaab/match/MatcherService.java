package in.moonman.hishaab.match;

import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API of the matcher module (SPEC-031). Turns parsed records into transactions with evidence.
 */
public interface MatcherService {

    /**
     * Matches one parsed record against existing transactions (R-031-1 .. R-031-10).
     * Idempotent (AC-031-7). Records with {@code record_type=INVESTMENT_CONFIRMATION} are skipped (R-031-8).
     */
    MatchResult matchRecord(UUID parsedRecordId);

    /**
     * Resolves an EMAIL_ONLY review with ADD (R-031-9): creates a CONFIRMED transaction with MANUAL evidence.
     */
    UUID answerEmailOnlyAdd(UUID parsedRecordId);

    /**
     * Resolves an EMAIL_ONLY review with LINK (R-031-9): links email record to the selected transaction.
     */
    void answerEmailOnlyLink(UUID parsedRecordId, UUID transactionId);

    Optional<TransactionDetail> findTransaction(UUID transactionId);

    List<TransactionEvidenceView> getEvidence(UUID transactionId);

    record MatchResult(Outcome outcome, UUID transactionId, String reviewDedupKey) {
        public enum Outcome {
            CREATED_CONFIRMED,
            CREATED_PENDING,
            ATTACHED_REF,
            ATTACHED_WINDOW,
            EMAIL_ONLY_REVIEW,
            AMBIGUOUS_REVIEW,
            ALREADY_ATTACHED,
            SKIPPED
        }
    }

    record TransactionDetail(
            UUID id,
            long amountPaise,
            Direction direction,
            Instant txnTime,
            UUID accountId,
            UUID merchantId,
            String kind,
            String status,
            Channel channel,
            String refNo,
            List<TransactionEvidenceView> evidence
    ) {}

    record TransactionEvidenceView(
            UUID id,
            UUID transactionId,
            UUID parsedRecordId,
            String matchMethod,
            Double score,
            Instant createdAt
    ) {}
}
