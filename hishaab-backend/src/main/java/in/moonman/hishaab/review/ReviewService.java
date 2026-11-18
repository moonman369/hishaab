package in.moonman.hishaab.review;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Public API of the review module. SPEC-015 needs only "open a question" and "clear the open questions for a
 * message"; SPEC-031 adds auto-close on match; SPEC-033 adds listing, resolving and dismissing.
 */
public interface ReviewService {

    /**
     * Opens a review item unless one with the same {@code dedupKey} already exists (R-033-4).
     *
     * @return true if a new item was opened
     */
    boolean open(OpenReview request);

    /** Auto-closes an open review item by its dedup key (R-031-5, R-031-10). */
    boolean autoClose(String dedupKey);

    /** Deletes still-{@code OPEN} items about this raw message (derived data, R-015-7). Answered items are kept. */
    int deleteOpenForRawMessage(String rawMessageId);

    /** Deletes every still-{@code OPEN} item tied to a raw message (used by reprocess-all). */
    int deleteOpenForAllRawMessages();

    enum ReviewType {
        AMBIGUOUS_MATCH, UNPARSED, LOW_CONFIDENCE, UNKNOWN_CATEGORY, UNKNOWN_KIND, BALANCE_GAP, ORPHAN_OVERRIDE,
        INSTALMENT_AMBIGUOUS, SIP_SCHEDULE_SUGGESTION, EMAIL_ONLY, BALANCE_STALE
    }

    enum ReviewStatus { OPEN, RESOLVED, DISMISSED, AUTO_CLOSED }

    /** @param payload JSON-serialisable payload (SPEC-033 payload shapes) */
    record OpenReview(ReviewType type, String dedupKey, Map<String, Object> payload, String rawMessageId,
                      UUID transactionId) {

        public OpenReview {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(dedupKey, "dedupKey");
            Objects.requireNonNull(payload, "payload");
        }

        /** R-015-6: a message that looks like money but could not be parsed. */
        public static OpenReview unparsed(String rawMessageId, String reason) {
            return new OpenReview(ReviewType.UNPARSED, ReviewType.UNPARSED + ":" + rawMessageId,
                    Map.of("rawMessageId", rawMessageId, "reason", reason), rawMessageId, null);
        }
    }
}
