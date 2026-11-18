package in.moonman.hishaab.match;

import in.moonman.hishaab.match.MatcherService.MatchResult;
import in.moonman.hishaab.match.MatcherService.MatchResult.Outcome;
import in.moonman.hishaab.match.MatcherService.TransactionDetail;
import in.moonman.hishaab.match.MatcherService.TransactionEvidenceView;
import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.shared.Source;
import in.moonman.hishaab.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;

@RecordApplicationEvents
class MatcherServiceIT extends IntegrationTest {

    @Autowired
    private MatcherService matcher;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM transaction_evidence");
        jdbc.update("DELETE FROM transaction");
        jdbc.update("DELETE FROM review_item");
    }

    @Test
    void AC_031_01_smsWithRefThenEmailWithRefResultsInOneTransactionTwoEvidence() {
        String ref = "123456789012";
        Instant t0 = Instant.parse("2026-11-18T10:00:00Z");

        UUID smsRecord = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 50000, Direction.DR, Channel.UPI, ref, t0, "Swiggy");
        UUID emailRecord = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 50000, Direction.DR, Channel.UPI, ref, t0.plusSeconds(300), "Swiggy Ltd");

        MatchResult r1 = matcher.matchRecord(smsRecord);
        assertThat(r1.outcome()).isEqualTo(Outcome.CREATED_CONFIRMED);
        UUID txnId = r1.transactionId();

        MatchResult r2 = matcher.matchRecord(emailRecord);
        assertThat(r2.outcome()).isEqualTo(Outcome.ATTACHED_REF);
        assertThat(r2.transactionId()).isEqualTo(txnId);

        TransactionDetail txn = matcher.findTransaction(txnId).orElseThrow();
        assertThat(txn.evidence()).hasSize(2);
        assertThat(txn.evidence()).extracting(TransactionEvidenceView::matchMethod).containsExactlyInAnyOrder("FIRST", "REF");
    }

    @Test
    void AC_031_02_smsWithoutRefAndEmail40MinLaterMatchesByWindow() {
        Instant t0 = Instant.parse("2026-11-18T11:00:00Z");

        UUID smsRecord = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 75000, Direction.DR, Channel.UPI, null, t0, "Zomato");
        UUID emailRecord = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 75000, Direction.DR, Channel.UPI, null, t0.plus(Duration.ofMinutes(40)), "Zomato Media");

        MatchResult r1 = matcher.matchRecord(smsRecord);
        assertThat(r1.outcome()).isEqualTo(Outcome.CREATED_CONFIRMED);
        UUID txnId = r1.transactionId();

        MatchResult r2 = matcher.matchRecord(emailRecord);
        assertThat(r2.outcome()).isEqualTo(Outcome.ATTACHED_WINDOW);
        assertThat(r2.transactionId()).isEqualTo(txnId);

        TransactionDetail txn = matcher.findTransaction(txnId).orElseThrow();
        assertThat(txn.evidence()).hasSize(2);
        assertThat(txn.evidence()).extracting(TransactionEvidenceView::matchMethod).containsExactlyInAnyOrder("FIRST", "WINDOW");
    }

    @Test
    void AC_031_03_emailArrives5HoursLaterOpensEmailOnlyReview() {
        Instant t0 = Instant.parse("2026-11-18T05:00:00Z");

        UUID smsRecord = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 82000, Direction.DR, Channel.UPI, null, t0, "Uber");
        // 5 hours later is 300 minutes, exceeding standard window of 180 min
        UUID emailRecord = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 82000, Direction.DR, Channel.UPI, null, t0.plus(Duration.ofHours(5)), "Uber India");

        MatchResult r1 = matcher.matchRecord(smsRecord);
        assertThat(r1.outcome()).isEqualTo(Outcome.CREATED_CONFIRMED);

        MatchResult r2 = matcher.matchRecord(emailRecord);
        assertThat(r2.outcome()).isEqualTo(Outcome.EMAIL_ONLY_REVIEW);
        assertThat(r2.transactionId()).isNull();

        // Still only 1 transaction (the SMS one)
        TransactionDetail txn = matcher.findTransaction(r1.transactionId()).orElseThrow();
        assertThat(txn.evidence()).hasSize(1);

        // Review item was opened
        Integer openCount = jdbc.queryForObject(
                "SELECT count(*) FROM review_item WHERE type = 'EMAIL_ONLY' AND dedup_key = ?",
                Integer.class, "EMAIL_ONLY:" + emailRecord);
        assertThat(openCount).isEqualTo(1);
    }

    @Test
    void AC_031_04_multipleMatchingCandidatesOpensAmbiguousMatchReview() {
        Instant t0 = Instant.parse("2026-11-18T12:00:00Z");
        // Two identical amount debits 10 minutes apart on same account
        UUID sms1 = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 10000, Direction.DR, Channel.UPI, null, t0, "Shop A");
        UUID sms2 = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 10000, Direction.DR, Channel.UPI, null, t0.plus(Duration.ofMinutes(10)), "Shop B");

        matcher.matchRecord(sms1);
        matcher.matchRecord(sms2);

        // Email arriving 5 mins after second SMS could match either
        UUID email = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 10000, Direction.DR, Channel.UPI, null, t0.plus(Duration.ofMinutes(15)), "Shop C");
        MatchResult result = matcher.matchRecord(email);

        assertThat(result.outcome()).isEqualTo(Outcome.AMBIGUOUS_REVIEW);
        assertThat(result.transactionId()).isNull();

        Integer openCount = jdbc.queryForObject(
                "SELECT count(*) FROM review_item WHERE type = 'AMBIGUOUS_MATCH' AND dedup_key = ?",
                Integer.class, "AMBIGUOUS_MATCH:" + email);
        assertThat(openCount).isEqualTo(1);
    }

    @Test
    void AC_031_05_emailArrivesFirstCreatesPendingAndReviewThenSmsResolvesIt() {
        Instant t0 = Instant.parse("2026-11-18T13:00:00Z");
        String ref = "998877665544";

        // Email arrives first: per R-031-9, an email never creates a transaction
        UUID email = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 30000, Direction.DR, Channel.UPI, ref, t0, "BookMyShow");
        MatchResult r1 = matcher.matchRecord(email);

        assertThat(r1.outcome()).isEqualTo(Outcome.EMAIL_ONLY_REVIEW);
        assertThat(r1.transactionId()).isNull();

        // EMAIL_ONLY review was opened
        Integer openCount = jdbc.queryForObject(
                "SELECT count(*) FROM review_item WHERE type = 'EMAIL_ONLY' AND dedup_key = ? AND status = 'OPEN'",
                Integer.class, "EMAIL_ONLY:" + email);
        assertThat(openCount).isEqualTo(1);

        // Now SMS arrives 10 mins later with same ref: per R-031-10, SMS creates transaction and attaches open EMAIL_ONLY review
        UUID sms = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 30000, Direction.DR, Channel.UPI, ref, t0.plus(Duration.ofMinutes(10)), "BookMyShow");
        MatchResult r2 = matcher.matchRecord(sms);

        assertThat(r2.outcome()).isEqualTo(Outcome.CREATED_CONFIRMED);
        UUID txnId = r2.transactionId();

        // Transaction is now CONFIRMED with 2 evidence rows
        TransactionDetail confirmedTxn = matcher.findTransaction(txnId).orElseThrow();
        assertThat(confirmedTxn.status()).isEqualTo("CONFIRMED");
        assertThat(confirmedTxn.evidence()).hasSize(2);
        assertThat(confirmedTxn.evidence()).extracting(TransactionEvidenceView::matchMethod).containsExactlyInAnyOrder("FIRST", "REF");

        // Review item was auto-closed
        Integer openCountAfter = jdbc.queryForObject(
                "SELECT count(*) FROM review_item WHERE type = 'EMAIL_ONLY' AND dedup_key = ? AND status = 'OPEN'",
                Integer.class, "EMAIL_ONLY:" + email);
        assertThat(openCountAfter).isEqualTo(0);
    }

    @Test
    void AC_031_06_lateSmsMatchesPendingEmailByWindowAndPromotes() {
        Instant t0 = Instant.parse("2026-11-18T14:00:00Z");

        // Email arrives first without ref
        UUID email = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 45000, Direction.DR, Channel.UPI, null, t0, "CultFit");
        MatchResult r1 = matcher.matchRecord(email);
        assertThat(r1.outcome()).isEqualTo(Outcome.EMAIL_ONLY_REVIEW);
        assertThat(r1.transactionId()).isNull();

        // Late SMS arrives 20 mins later without ref
        UUID sms = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 45000, Direction.DR, Channel.UPI, null, t0.plus(Duration.ofMinutes(20)), "CultFit");
        MatchResult r2 = matcher.matchRecord(sms);

        assertThat(r2.outcome()).isEqualTo(Outcome.CREATED_CONFIRMED);
        UUID txnId = r2.transactionId();

        TransactionDetail confirmedTxn = matcher.findTransaction(txnId).orElseThrow();
        assertThat(confirmedTxn.status()).isEqualTo("CONFIRMED");
        assertThat(confirmedTxn.evidence()).hasSize(2);
        assertThat(confirmedTxn.evidence()).extracting(TransactionEvidenceView::matchMethod).containsExactlyInAnyOrder("FIRST", "WINDOW");
    }

    @Test
    void AC_031_07_emailEnrichesMerchantWhenSmsDidNotHaveIt() {
        Instant t0 = Instant.parse("2026-11-18T15:00:00Z");
        String ref = "554433221100";

        // SMS without counterparty / merchant
        UUID sms = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 12000, Direction.DR, Channel.UPI, ref, t0, null);
        MatchResult r1 = matcher.matchRecord(sms);
        UUID txnId = r1.transactionId();

        TransactionDetail tBefore = matcher.findTransaction(txnId).orElseThrow();
        assertThat(tBefore.merchantId()).isNull();

        // Email with merchant
        UUID email = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 12000, Direction.DR, Channel.UPI, ref, t0.plusSeconds(60), "Amazon India");
        matcher.matchRecord(email);

        TransactionDetail tAfter = matcher.findTransaction(txnId).orElseThrow();
        assertThat(tAfter.merchantId()).isNotNull();
    }

    @Test
    void AC_031_08_idempotencyReplayingParsedRecordIdReturnsAlreadyAttached() {
        Instant t0 = Instant.parse("2026-11-18T16:00:00Z");
        UUID sms = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 6000, Direction.DR, Channel.UPI, "11223344", t0, "Chai Point");

        MatchResult r1 = matcher.matchRecord(sms);
        assertThat(r1.outcome()).isEqualTo(Outcome.CREATED_CONFIRMED);

        MatchResult r2 = matcher.matchRecord(sms);
        assertThat(r2.outcome()).isEqualTo(Outcome.ALREADY_ATTACHED);
        assertThat(r2.transactionId()).isEqualTo(r1.transactionId());
    }

    @Test
    void AC_031_09_creditAndDebitWithSameRefDoNotCollide() {
        Instant t0 = Instant.parse("2026-11-18T17:00:00Z");
        String sameRef = "123456789012";

        UUID dr = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 10000, Direction.DR, Channel.UPI, sameRef, t0, "Merchant A");
        UUID cr = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 10000, Direction.CR, Channel.UPI, sameRef, t0.plusSeconds(30), "Merchant A");

        MatchResult r1 = matcher.matchRecord(dr);
        MatchResult r2 = matcher.matchRecord(cr);

        assertThat(r1.transactionId()).isNotEqualTo(r2.transactionId());
    }

    @Test
    void AC_031_10_transactionCreatedAndUpdatedEventsArePublished() {
        Instant t0 = Instant.parse("2026-11-18T18:00:00Z");
        String ref = "987654321098";

        UUID sms = insertRecord(Source.SMS, "AX-AXISBK-S", "7311", 25000, Direction.DR, Channel.UPI, ref, t0, "Blinkit");
        MatchResult r1 = matcher.matchRecord(sms);

        long createdEvents = events.stream(TransactionCreated.class)
                .filter(e -> e.transactionId().equals(r1.transactionId()))
                .count();
        assertThat(createdEvents).isEqualTo(1);

        UUID email = insertRecord(Source.EMAIL, "alerts@axisbank.com", "7311", 25000, Direction.DR, Channel.UPI, ref, t0.plusSeconds(60), "Blinkit Commerce");
        matcher.matchRecord(email);

        long updatedEvents = events.stream(TransactionUpdated.class)
                .filter(e -> e.transactionId().equals(r1.transactionId()))
                .count();
        assertThat(updatedEvents).isEqualTo(1);
    }

    private UUID insertRecord(Source source, String sender, String last4, long amountPaise, Direction direction,
                              Channel channel, String refNo, Instant txnTime, String cpRaw) {
        String rawMsgId = Ids.sha256Hex(sender + "\n" + UUID.randomUUID() + "\n" + txnTime.toEpochMilli());
        OffsetDateTime odt = OffsetDateTime.ofInstant(txnTime, ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO raw_message (id, source, sender, body, received_at, status)
                VALUES (?, ?, ?, 'body', ?, 'PARSED')
                """, rawMsgId, source.name(), sender, odt);

        UUID recordId = Ids.newId();
        jdbc.update("""
                INSERT INTO parsed_record (id, raw_message_id, parser, record_type, amount_paise, direction,
                                           account_last4, channel, counterparty_raw, ref_no, txn_time, confidence)
                VALUES (?, ?, 'LLM', 'TRANSACTION', ?, ?, ?, ?, ?, ?, ?, 1.0)
                """, recordId, rawMsgId, amountPaise, direction.name(), last4, channel != null ? channel.name() : null, cpRaw, refNo, odt);

        return recordId;
    }
}
