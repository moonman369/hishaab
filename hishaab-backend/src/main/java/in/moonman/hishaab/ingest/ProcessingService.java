package in.moonman.hishaab.ingest;

/**
 * Public API of message processing (SPEC-015): drives raw messages through classify → parse → store → review.
 * Only one processor runs at a time across the whole system (advisory lock, R-015-2).
 */
public interface ProcessingService {

    /**
     * Processes every {@code NEW} raw message, oldest first (R-015-3). Returns immediately with
     * {@link ProcessingResult#notRun()} if another processor holds the lock; that processor picks the work up.
     */
    ProcessingResult processPending();

    /** Clears derived rows for one message, sets it back to {@code NEW} and processes it (R-015-7). Waits for the lock. */
    ProcessingResult reprocess(String rawMessageId);

    /** Clears all derived rows, sets every message back to {@code NEW} and processes everything (R-015-7, SPEC-034). */
    ProcessingResult reprocessAll();

    /** Counts for one run (used by the daily report, SPEC-036). */
    record ProcessingResult(boolean ran, int processed, int parsed, int ignored, int failed, int unparsed, int errors) {

        public static ProcessingResult notRun() {
            return new ProcessingResult(false, 0, 0, 0, 0, 0, 0);
        }

        public ProcessingResult plus(ProcessingResult o) {
            return new ProcessingResult(ran || o.ran, processed + o.processed, parsed + o.parsed, ignored + o.ignored,
                    failed + o.failed, unparsed + o.unparsed, errors + o.errors);
        }
    }
}
