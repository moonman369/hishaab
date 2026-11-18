package in.moonman.hishaab.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.classify.MessageClassified;
import in.moonman.hishaab.ingest.ProcessingService.ProcessingResult;
import in.moonman.hishaab.parse.ParseService;
import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.parse.RecordParsed;
import in.moonman.hishaab.parse.internal.TestParsers;
import in.moonman.hishaab.shared.AdvisoryLocks;
import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.shared.Source;
import in.moonman.hishaab.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * SPEC-015. The database is shared with other test classes, so assertions only look at this test's own messages
 * (bodies carry a unique nonce). Bodies are synthetic (HLD D-25).
 */
@Import({ProcessingIT.Recorder.class, ProcessingIT.ExplodingParserConfig.class})
class ProcessingIT extends IntegrationTest {

    static final String BOOM = "BOOM-IN-PARSER";

    @Autowired
    ProcessingService processing;

    @Autowired
    AdvisoryLocks locks;

    @Autowired
    Recorder recorder;

    private String nonce;
    private long t0;

    @BeforeEach
    void setUp() {
        nonce = Ids.newId().toString().substring(0, 8);
        t0 = OffsetDateTime.parse("2026-11-15T10:00:00+05:30").toInstant().toEpochMilli();
        recorder.clear();
    }

    // --- R-015-5 / R-015-8 ----------------------------------------------------------------------------

    @Test
    void R_015_5_transactionWithTemplateIsParsedAndAnnounced() {
        String id = insert("AX-AXISBK-S", axisUpi("120.00", "512300000001", "TEA STALL"), 0);

        processing.processPending();

        assertThat(status(id)).isEqualTo("PARSED");
        Map<String, Object> rec = jdbc.queryForMap("SELECT * FROM parsed_record WHERE raw_message_id = ?", id);
        assertThat(rec.get("template_id")).isEqualTo("axis.sms.upi");
        assertThat(rec.get("record_type")).isEqualTo("TRANSACTION");
        assertThat(rec.get("amount_paise")).isEqualTo(12_000L);
        assertThat(rec.get("parser")).isEqualTo("TEMPLATE");
        assertThat(recorder.parsed).extracting(RecordParsed::rawMessageId).containsExactly(id);
        assertThat(recorder.classified).extracting(MessageClassified::rawMessageId).containsExactly(id);
    }

    @Test
    void R_015_8_fundHouseAllotmentIsParsedAsInvestmentConfirmation() {
        String id = insert("VK-ALPHMF-S", "Your Purchase of Rs.999.95 for folio 7000123/38 in Beta Small Cap "
                + "Fund-Reg-Growth is processed at NAV 50.357 - Team Beta MF #" + nonce, 0);

        processing.processPending();

        assertThat(status(id)).isEqualTo("PARSED");
        assertThat(jdbc.queryForObject("SELECT record_type FROM parsed_record WHERE raw_message_id = ?", String.class, id))
                .isEqualTo("INVESTMENT_CONFIRMATION");
        assertThat(recorder.parsed).singleElement()
                .satisfies(e -> assertThat(e.recordType()).isEqualTo(ParsedMessage.RecordType.INVESTMENT_CONFIRMATION));
    }

    // --- AC-015-3 / R-012-4 ---------------------------------------------------------------------------

    @Test
    void AC_015_03_otpIsIgnoredWithReasonAndNoParsedRecord() {
        String id = insert("AX-AXISBK-S", "482913 is your OTP for txn of Rs 500 at SHOPKART. Do not share. #" + nonce, 0);

        processing.processPending();

        assertThat(status(id)).isEqualTo("IGNORED");
        assertThat(ignoreReason(id)).isEqualTo("OTP");
        assertThat(parsedCount(id)).isZero();
        assertThat(reviewCount(id)).isZero();
    }

    @Test
    void R_012_4_failedTransactionIsMarkedFailed() {
        String id = insert("AX-AXISBK-S", "Txn of Rs 250 failed at QUICKBITE due to insufficient balance #" + nonce, 0);

        processing.processPending();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(ignoreReason(id)).isEqualTo("FAILED_TXN");
    }

    // --- AC-015-4 / R-015-6 ---------------------------------------------------------------------------

    @Test
    void AC_015_04_transactionWithoutTemplateOpensOneUnparsedReviewAndNoRecord() {
        String id = insert("AX-AXISBK-S", "Rs 500.00 debited from your account via NEFT to SOMEONE #" + nonce, 0);

        processing.processPending();
        processing.reprocess(id); // processing again must not open a second item

        assertThat(status(id)).isEqualTo("CLASSIFIED");
        assertThat(parsedCount(id)).isZero();
        assertThat(jdbc.queryForList("SELECT type FROM review_item WHERE raw_message_id = ?", String.class, id))
                .containsExactly("UNPARSED");
        assertThat(recorder.parsed).isEmpty();
    }

    @Test
    void R_015_6_unknownWithAmountOpensReviewWithoutAmountDoesNot() {
        String withAmount = insert("AX-AXISBK-S", "Your FD no. X7788 is booked for INR 20000 for 12M #" + nonce, 0);
        String withoutAmount = insert("AX-AXISBK-S", "Your KYC has been updated #" + nonce, 1);

        processing.processPending();

        assertThat(status(withAmount)).isEqualTo("CLASSIFIED");
        assertThat(reviewCount(withAmount)).isEqualTo(1);
        assertThat(status(withoutAmount)).isEqualTo("CLASSIFIED");
        assertThat(reviewCount(withoutAmount)).isZero();
    }

    @Test
    void R_015_6_fundHouseAllotmentWithoutTemplateIsUnparsed() {
        String id = insert("VK-ALPHMF-S", "Your SIP in folio 7000123 is processed and units are allotted at NAV #"
                + nonce, 0);

        processing.processPending();

        assertThat(status(id)).isEqualTo("CLASSIFIED");
        assertThat(reviewCount(id)).isEqualTo(1);
    }

    // --- AC-015-2 / R-015-4 ---------------------------------------------------------------------------

    @Test
    void AC_015_02_failureMarksOnlyThatMessageErrorAndTheNextIsProcessed() {
        String boom = insert("AX-AXISBK-S", axisUpi("10.00", "512300000002", BOOM), 0);
        String next = insert("AX-AXISBK-S", axisUpi("20.00", "512300000003", "NEXT SHOP"), 1);

        ProcessingResult result = processing.processPending();

        assertThat(status(boom)).isEqualTo("ERROR");
        assertThat(ignoreReason(boom)).isEqualTo(IllegalStateException.class.getName());
        assertThat(parsedCount(boom)).isZero();
        assertThat(status(next)).isEqualTo("PARSED");
        assertThat(result.errors()).isGreaterThanOrEqualTo(1);
        assertThat(recorder.parsed).extracting(RecordParsed::rawMessageId).contains(next).doesNotContain(boom);
    }

    // --- AC-015-1 / R-015-2 ---------------------------------------------------------------------------

    @Test
    void AC_015_01_triggerWhileLockIsHeldIsSkipped() throws Exception {
        String id = insert("AX-AXISBK-S", axisUpi("30.00", "512300000004", "LOCKED SHOP"), 0);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> locks.tryWithLock(AdvisoryLocks.PROCESSING, () -> {
                held.countDown();
                await(release);
                return true;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            ProcessingResult skipped = processing.processPending();

            assertThat(skipped.ran()).isFalse();
            assertThat(status(id)).isEqualTo("NEW");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        processing.processPending();
        assertThat(status(id)).isEqualTo("PARSED");
    }

    @Test
    void AC_015_01_twoConcurrentTriggersProcessEachMessageExactlyOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(insert("AX-AXISBK-S", axisUpi(i + 1 + ".00", "5124000000" + String.format("%02d", i), "SHOP " + i), i));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ProcessingResult>> runs = List.of(
                    pool.submit(() -> { await(start); return processing.processPending(); }),
                    pool.submit(() -> { await(start); return processing.processPending(); }));
            start.countDown();
            for (Future<ProcessingResult> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).allSatisfy(id -> assertThat(status(id)).isEqualTo("PARSED"));
        List<String> announced = recorder.parsed.stream().map(RecordParsed::rawMessageId).filter(ids::contains).toList();
        assertThat(announced).hasSize(ids.size()).doesNotHaveDuplicates();
    }

    // --- R-015-3 ---------------------------------------------------------------------------------------

    @Test
    void R_015_3_oldestFirst() {
        String later = insert("AX-AXISBK-S", axisUpi("2.00", "512300000006", "LATER"), 50);
        String earlier = insert("AX-AXISBK-S", axisUpi("1.00", "512300000005", "EARLIER"), 10);

        processing.processPending();

        List<String> order = recorder.parsed.stream().map(RecordParsed::rawMessageId)
                .filter(i -> i.equals(earlier) || i.equals(later)).toList();
        assertThat(order).containsExactly(earlier, later);
    }

    // --- R-015-7 ---------------------------------------------------------------------------------------

    @Test
    void R_015_7_reprocessClearsDerivedRowsFirst() {
        String id = insert("AX-AXISBK-S", axisUpi("75.00", "512300000007", "AGAIN SHOP"), 0);
        processing.processPending();
        String firstRecord = jdbc.queryForObject("SELECT id::text FROM parsed_record WHERE raw_message_id = ?",
                String.class, id);

        ProcessingResult again = processing.reprocess(id);

        assertThat(again.parsed()).isEqualTo(1);
        assertThat(parsedCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id::text FROM parsed_record WHERE raw_message_id = ?", String.class, id))
                .isNotEqualTo(firstRecord);
        assertThat(status(id)).isEqualTo("PARSED");
    }

    @Test
    void R_015_7_reprocessAllRebuildsTheSameResult() {
        String parsed = insert("AX-AXISBK-S", axisUpi("88.00", "512300000008", "ALL SHOP"), 0);
        String unparsed = insert("AX-AXISBK-S", "Rs 99.00 debited via NEFT to ANOTHER #" + nonce, 1);
        String otp = insert("AX-AXISBK-S", "123456 is your OTP for Rs 10 #" + nonce, 2);
        processing.processPending();

        processing.reprocessAll();

        assertThat(status(parsed)).isEqualTo("PARSED");
        assertThat(parsedCount(parsed)).isEqualTo(1);
        assertThat(status(unparsed)).isEqualTo("CLASSIFIED");
        assertThat(reviewCount(unparsed)).isEqualTo(1);
        assertThat(status(otp)).isEqualTo("IGNORED");
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private String axisUpi(String amount, String ref, String merchant) {
        return "INR " + amount + " debited\nA/c no. XX7311\n15-11-26, 10:00:00\nUPI/P2M/" + ref + "/" + merchant
                + "\nNot you? SMS BLOCKUPI Cust ID to 919000000000\nAxis Bank #" + nonce;
    }

    /** Inserts a NEW raw SMS directly (ingest is tested in IngestSmsIT); {@code offsetSeconds} orders them. */
    private String insert(String sender, String body, int offsetSeconds) {
        long receivedAtMs = t0 + offsetSeconds * 1000L;
        String id = SmsId.of(sender, body, receivedAtMs);
        jdbc.update("INSERT INTO raw_message (id, source, sender, body, received_at, status) VALUES (?, 'SMS', ?, ?, ?, 'NEW')",
                id, sender, body, Timestamp.from(Instant.ofEpochMilli(receivedAtMs)));
        return id;
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM raw_message WHERE id = ?", String.class, id);
    }

    private String ignoreReason(String id) {
        return jdbc.queryForObject("SELECT ignore_reason FROM raw_message WHERE id = ?", String.class, id);
    }

    private int parsedCount(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM parsed_record WHERE raw_message_id = ?", Integer.class, id);
    }

    private int reviewCount(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM review_item WHERE raw_message_id = ?", Integer.class, id);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration
    static class Recorder {
        final List<RecordParsed> parsed = new CopyOnWriteArrayList<>();
        final List<MessageClassified> classified = new CopyOnWriteArrayList<>();

        void clear() {
            parsed.clear();
            classified.clear();
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(RecordParsed e) {
            parsed.add(e);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(MessageClassified e) {
            classified.add(e);
        }
    }

    /** AC-015-2: the real parser, except it throws for bodies containing {@link #BOOM}. */
    @TestConfiguration
    static class ExplodingParserConfig {
        @Bean
        @Primary
        ParseService explodingParser() {
            ParseService real = TestParsers.fromClasspath();
            return (Source source, String sender, String body, Instant receivedAt) -> {
                if (body.contains(BOOM)) {
                    throw new IllegalStateException("simulated parser failure");
                }
                Optional<ParsedMessage> result = real.parse(source, sender, body, receivedAt);
                return result;
            };
        }
    }
}
