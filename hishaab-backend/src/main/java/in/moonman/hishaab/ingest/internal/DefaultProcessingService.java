package in.moonman.hishaab.ingest.internal;

import in.moonman.hishaab.ingest.ProcessingService;
import in.moonman.hishaab.ingest.internal.MessageProcessor.Outcome;
import in.moonman.hishaab.parse.ParsedRecords;
import in.moonman.hishaab.review.ReviewService;
import in.moonman.hishaab.shared.AdvisoryLocks;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-015 orchestrator. One processor at a time via {@link AdvisoryLocks#PROCESSING} (R-015-2); NEW messages
 * oldest first in batches of 100 (R-015-3), each in its own transaction (R-015-4).
 */
@Service
class DefaultProcessingService implements ProcessingService {

    static final int BATCH_SIZE = 100;
    private static final int MAX_ROUNDS = 5;
    private static final Duration REPROCESS_ONE_WAIT = Duration.ofSeconds(60);
    private static final Duration REPROCESS_ALL_WAIT = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(DefaultProcessingService.class);

    private final AdvisoryLocks locks;
    private final MessageProcessor processor;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ParsedRecords parsedRecords;
    private final ReviewService review;

    DefaultProcessingService(AdvisoryLocks locks, MessageProcessor processor, JdbcTemplate jdbc,
                             TransactionTemplate tx, ParsedRecords parsedRecords, ReviewService review) {
        this.locks = locks;
        this.processor = processor;
        this.jdbc = jdbc;
        this.tx = tx;
        this.parsedRecords = parsedRecords;
        this.review = review;
    }

    @Override
    public ProcessingResult processPending() {
        ProcessingResult total = ProcessingResult.notRun();
        // After releasing the lock, look again: a trigger that was skipped while we held the lock relies on us.
        for (int round = 0; round < MAX_ROUNDS; round++) {
            var result = locks.tryWithLock(AdvisoryLocks.PROCESSING, this::drainNew);
            if (result.isEmpty()) {
                return total; // someone else is processing; they will pick these messages up
            }
            total = total.plus(result.get());
            if (!hasNew()) {
                break;
            }
        }
        if (total.processed() > 0) {
            log.info("event=processing_run processed={} parsed={} ignored={} failed={} unparsed={} errors={}",
                    total.processed(), total.parsed(), total.ignored(), total.failed(), total.unparsed(), total.errors());
        }
        return total;
    }

    @Override
    public ProcessingResult reprocess(String rawMessageId) {
        return locks.withLock(AdvisoryLocks.PROCESSING, REPROCESS_ONE_WAIT, () -> {
            tx.executeWithoutResult(s -> {
                parsedRecords.deleteForRawMessage(rawMessageId);
                review.deleteOpenForRawMessage(rawMessageId);
                jdbc.update("UPDATE raw_message SET status = 'NEW', ignore_reason = NULL WHERE id = ?", rawMessageId);
            });
            return count(List.of(processor.process(rawMessageId)));
        }).orElseThrow(() -> new IllegalStateException("processing lock not available for reprocess"));
    }

    @Override
    public ProcessingResult reprocessAll() {
        return locks.withLock(AdvisoryLocks.PROCESSING, REPROCESS_ALL_WAIT, () -> {
            tx.executeWithoutResult(s -> {
                review.deleteOpenForAllRawMessages();
                parsedRecords.deleteAll();
                jdbc.update("UPDATE raw_message SET status = 'NEW', ignore_reason = NULL WHERE status <> 'NEW'");
            });
            return drainNew();
        }).orElseThrow(() -> new IllegalStateException("processing lock not available for reprocess-all"));
    }

    /** Must be called with the lock held. */
    private ProcessingResult drainNew() {
        ProcessingResult total = new ProcessingResult(true, 0, 0, 0, 0, 0, 0);
        Set<String> attempted = new HashSet<>();
        while (true) {
            List<String> batch = jdbc.queryForList(
                    "SELECT id FROM raw_message WHERE status = 'NEW' ORDER BY received_at, id LIMIT ?",
                    String.class, BATCH_SIZE);
            batch = batch.stream().filter(attempted::add).toList();
            if (batch.isEmpty()) {
                return total; // nothing new, or only messages we could not even mark ERROR (no spinning)
            }
            List<Outcome> outcomes = batch.stream().map(processor::process).toList();
            total = total.plus(count(outcomes));
        }
    }

    private boolean hasNew() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM raw_message WHERE status = 'NEW')", Boolean.class));
    }

    private static ProcessingResult count(List<Outcome> outcomes) {
        int processed = 0, parsed = 0, ignored = 0, failed = 0, unparsed = 0, errors = 0;
        for (Outcome o : outcomes) {
            if (o == Outcome.SKIPPED) {
                continue;
            }
            processed++;
            switch (o) {
                case PARSED -> parsed++;
                case IGNORED -> ignored++;
                case FAILED -> failed++;
                case UNPARSED -> unparsed++;
                case ERROR -> errors++;
                default -> { }
            }
        }
        return new ProcessingResult(true, processed, parsed, ignored, failed, unparsed, errors);
    }
}
