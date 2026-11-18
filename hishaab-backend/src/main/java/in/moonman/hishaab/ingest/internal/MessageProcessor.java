package in.moonman.hishaab.ingest.internal;

import in.moonman.hishaab.classify.Classification;
import in.moonman.hishaab.classify.ClassifyService;
import in.moonman.hishaab.classify.MessageClass;
import in.moonman.hishaab.classify.MessageClassified;
import in.moonman.hishaab.parse.ParseService;
import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.parse.ParsedMessage.RecordType;
import in.moonman.hishaab.parse.ParsedRecords;
import in.moonman.hishaab.parse.RecordParsed;
import in.moonman.hishaab.review.ReviewService;
import in.moonman.hishaab.review.ReviewService.OpenReview;
import in.moonman.hishaab.shared.RawStatus;
import in.moonman.hishaab.shared.Source;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes ONE raw message in its own transaction (R-015-4). The caller must hold the processing lock.
 * Never logs message bodies (H-6).
 */
@Component
class MessageProcessor {

    enum Outcome { PARSED, IGNORED, FAILED, UNPARSED, CLASSIFIED, SKIPPED, ERROR }

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ClassifyService classifier;
    private final ParseService parser;
    private final ParsedRecords parsedRecords;
    private final ReviewService review;
    private final ApplicationEventPublisher events;

    MessageProcessor(JdbcTemplate jdbc, TransactionTemplate tx, ClassifyService classifier, ParseService parser,
                     ParsedRecords parsedRecords, ReviewService review, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.classifier = classifier;
        this.parser = parser;
        this.parsedRecords = parsedRecords;
        this.review = review;
        this.events = events;
    }

    private record Raw(String id, Source source, String sender, String body, Instant receivedAt, RawStatus status) {
    }

    Outcome process(String rawMessageId) {
        try {
            return tx.execute(status -> processInTransaction(rawMessageId));
        } catch (RuntimeException e) {
            // R-015-4: the message's own transaction rolled back; record the failure and move on.
            log.warn("event=message_processing_error rawMessageId={} exception={}", rawMessageId, e.getClass().getName());
            tx.executeWithoutResult(status -> jdbc.update(
                    "UPDATE raw_message SET status = 'ERROR', ignore_reason = ? WHERE id = ? AND status = 'NEW'",
                    e.getClass().getName(), rawMessageId));
            return Outcome.ERROR;
        }
    }

    private Outcome processInTransaction(String rawMessageId) {
        Optional<Raw> loaded = load(rawMessageId);
        if (loaded.isEmpty() || loaded.get().status() != RawStatus.NEW) {
            return Outcome.SKIPPED; // already done by someone else; never process twice (AC-015-1)
        }
        Raw raw = loaded.get();
        Classification c = classifier.classify(raw.sender(), raw.body());

        if (c.rawStatus() != RawStatus.CLASSIFIED) { // IGNORED or FAILED, with the class as reason (R-012-4)
            setStatus(raw.id(), c.rawStatus(), c.ignoreReason());
            return c.rawStatus() == RawStatus.FAILED ? Outcome.FAILED : Outcome.IGNORED;
        }

        if (c.messageClass() == MessageClass.UNKNOWN) {
            setStatus(raw.id(), RawStatus.CLASSIFIED, null);
            if (classifier.mentionsAmount(raw.body())) { // R-015-6
                review.open(OpenReview.unparsed(raw.id(), "UNKNOWN_WITH_AMOUNT"));
                return Outcome.UNPARSED;
            }
            return Outcome.CLASSIFIED;
        }

        // TRANSACTION or INVESTMENT_CONFIRMATION
        events.publishEvent(new MessageClassified(raw.id()));
        RecordType expected = c.messageClass() == MessageClass.INVESTMENT_CONFIRMATION
                ? RecordType.INVESTMENT_CONFIRMATION : RecordType.TRANSACTION;
        Optional<ParsedMessage> parsed = parser.parse(raw.source(), raw.sender(), raw.body(), raw.receivedAt());

        if (parsed.isEmpty() || parsed.get().recordType() != expected) {
            // R-015-6: no template, or the template disagrees with the classifier → ask, never guess (H-9).
            setStatus(raw.id(), RawStatus.CLASSIFIED, null);
            review.open(OpenReview.unparsed(raw.id(), parsed.isEmpty() ? "NO_TEMPLATE" : "TEMPLATE_CLASS_MISMATCH"));
            return Outcome.UNPARSED;
        }

        UUID recordId = parsedRecords.save(raw.id(), parsed.get()); // R-015-5 / R-015-8
        setStatus(raw.id(), RawStatus.PARSED, null);
        events.publishEvent(new RecordParsed(recordId, raw.id(), expected));
        return Outcome.PARSED;
    }

    private Optional<Raw> load(String id) {
        List<Raw> rows = jdbc.query(
                "SELECT id, source, sender, body, received_at, status FROM raw_message WHERE id = ? FOR UPDATE",
                (rs, n) -> new Raw(rs.getString("id"), Source.valueOf(rs.getString("source")), rs.getString("sender"),
                        rs.getString("body"), rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                        RawStatus.valueOf(rs.getString("status"))),
                id);
        return rows.stream().findFirst();
    }

    private void setStatus(String id, RawStatus status, String ignoreReason) {
        jdbc.update("UPDATE raw_message SET status = ?, ignore_reason = ? WHERE id = ?", status.name(), ignoreReason, id);
    }
}
