package in.moonman.hishaab.ingest.internal;

import in.moonman.hishaab.ingest.ProcessingService;
import in.moonman.hishaab.ingest.RawMessageReceived;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * R-015-1: processing is triggered after each committed SMS upload and once at startup (SPEC-036 adds the daily
 * scan). Runs asynchronously so uploads return fast. Disabled with {@code hishaab.processing.auto-trigger=false}
 * (the test profile does this so tests drive processing explicitly).
 */
@Component
@ConditionalOnProperty(name = "hishaab.processing.auto-trigger", havingValue = "true", matchIfMissing = true)
class ProcessingTriggers {

    private static final Logger log = LoggerFactory.getLogger(ProcessingTriggers.class);

    private final ProcessingService processing;

    ProcessingTriggers(ProcessingService processing) {
        this.processing = processing;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRawMessageReceived(RawMessageReceived event) {
        run("raw_message_received");
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        run("startup");
    }

    private void run(String trigger) {
        try {
            processing.processPending();
        } catch (RuntimeException e) {
            log.error("event=processing_trigger_failed trigger={} exception={}", trigger, e.getClass().getName(), e);
        }
    }
}
