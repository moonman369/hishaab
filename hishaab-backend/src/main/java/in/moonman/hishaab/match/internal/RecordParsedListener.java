package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.MatcherService;
import in.moonman.hishaab.parse.ParsedMessage.RecordType;
import in.moonman.hishaab.parse.RecordParsed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Automatically triggers the matcher when a new TRANSACTION record is parsed (SPEC-015 pipeline chain).
 */
@Component
class RecordParsedListener {

    private static final Logger log = LoggerFactory.getLogger(RecordParsedListener.class);

    private final MatcherService matcher;

    RecordParsedListener(MatcherService matcher) {
        this.matcher = matcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRecordParsed(RecordParsed event) {
        if (event.recordType() == RecordType.TRANSACTION) {
            try {
                matcher.matchRecord(event.parsedRecordId());
            } catch (RuntimeException e) {
                log.error("event=matching_failed parsedRecordId={} exception={}",
                        event.parsedRecordId(), e.getClass().getName(), e);
            }
        }
    }
}
