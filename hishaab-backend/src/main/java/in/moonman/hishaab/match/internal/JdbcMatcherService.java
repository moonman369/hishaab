package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.AccountMerged;
import in.moonman.hishaab.match.AccountService;
import in.moonman.hishaab.match.AccountService.AccountRef;
import in.moonman.hishaab.match.MatcherService;
import in.moonman.hishaab.match.MerchantMerged;
import in.moonman.hishaab.match.MerchantService;
import in.moonman.hishaab.match.MerchantService.MerchantRef;
import in.moonman.hishaab.match.TransactionCreated;
import in.moonman.hishaab.match.TransactionUpdated;
import in.moonman.hishaab.match.TransactionUpdated.MatchMethod;
import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.review.ReviewService;
import in.moonman.hishaab.review.ReviewService.OpenReview;
import in.moonman.hishaab.review.ReviewService.ReviewType;
import in.moonman.hishaab.settings.SettingsService;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.shared.SenderIds;
import in.moonman.hishaab.shared.Source;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcMatcherService implements MatcherService {

    private static final Logger log = LoggerFactory.getLogger(JdbcMatcherService.class);

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final MerchantService merchants;
    private final SettingsService settings;
    private final ReviewService review;
    private final ApplicationEventPublisher events;

    JdbcMatcherService(JdbcTemplate jdbc, AccountService accounts, MerchantService merchants,
                       SettingsService settings, ReviewService review, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.merchants = merchants;
        this.settings = settings;
        this.review = review;
        this.events = events;
    }

    private record ParsedRow(
            UUID id,
            String rawMessageId,
            Source source,
            String sender,
            String recordType,
            long amountPaise,
            Direction direction,
            String accountLast4,
            Channel channel,
            String counterpartyRaw,
            String refNo,
            Instant txnTime,
            Long balanceAfterPaise
    ) {}

    private record TxnCandidate(
            UUID id,
            long amountPaise,
            Direction direction,
            Instant txnTime,
            UUID accountId,
            UUID merchantId,
            String kind,
            String status,
            Channel channel,
            String refNo
    ) {}

    @Override
    @Transactional
    public MatchResult matchRecord(UUID parsedRecordId) {
        ParsedRow record = loadParsedRow(parsedRecordId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARSED_RECORD_NOT_FOUND", "Record not found: " + parsedRecordId));

        // R-031-8: Records with record_type=INVESTMENT_CONFIRMATION are never turned into transactions
        if ("INVESTMENT_CONFIRMATION".equals(record.recordType())) {
            return new MatchResult(MatchResult.Outcome.SKIPPED, null, null);
        }

        // AC-031-7: Check if already attached
        Optional<UUID> existingTxn = findTransactionByEvidence(parsedRecordId);
        if (existingTxn.isPresent()) {
            return new MatchResult(MatchResult.Outcome.ALREADY_ATTACHED, existingTxn.get(), null);
        }

        // Resolve account & merchant
        UUID accountId = resolveAccount(record);
        UUID merchantId = resolveMerchant(record.counterpartyRaw());

        // R-031-1: Ref match
        if (record.refNo() != null && !record.refNo().isBlank()) {
            Optional<TxnCandidate> refMatch = findByRef(record.refNo(), record.direction());
            if (refMatch.isPresent()) {
                TxnCandidate txn = refMatch.get();
                attachEvidence(txn.id(), parsedRecordId, "REF", 1.0);
                fillNullFields(txn, record, accountId, merchantId);
                events.publishEvent(new TransactionUpdated(txn.id(), parsedRecordId, MatchMethod.REF));
                return new MatchResult(MatchResult.Outcome.ATTACHED_REF, txn.id(), null);
            }
        }

        // R-031-3: Window match
        int windowMinutes = settings.matchSmsEmailWindowMinutes();
        List<TxnCandidate> candidates = findWindowCandidates(record, accountId, Duration.ofMinutes(windowMinutes));

        if (candidates.isEmpty()) {
            // 0 candidates
            if (record.source() == Source.SMS) {
                // SMS record: create new transaction
                try {
                    UUID txnId = createTransaction(record, accountId, merchantId, "CONFIRMED");
                    attachEvidence(txnId, parsedRecordId, "FIRST", 1.0);
                    events.publishEvent(new TransactionCreated(txnId, parsedRecordId));

                    // R-031-10: check if any open EMAIL_ONLY review items now match this transaction
                    checkAndAttachOpenEmailOnly(txnId, record, accountId, Duration.ofMinutes(windowMinutes));

                    return new MatchResult(MatchResult.Outcome.CREATED_CONFIRMED, txnId, null);
                } catch (DuplicateKeyException e) {
                    // R-031-7: retry once as a ref match
                    if (record.refNo() != null) {
                        Optional<TxnCandidate> retryMatch = findByRef(record.refNo(), record.direction());
                        if (retryMatch.isPresent()) {
                            TxnCandidate txn = retryMatch.get();
                            attachEvidence(txn.id(), parsedRecordId, "REF", 1.0);
                            fillNullFields(txn, record, accountId, merchantId);
                            events.publishEvent(new TransactionUpdated(txn.id(), parsedRecordId, MatchMethod.REF));
                            return new MatchResult(MatchResult.Outcome.ATTACHED_REF, txn.id(), null);
                        }
                    }
                    throw e;
                }
            } else {
                // Email record: R-031-9 An email never creates a transaction
                String dedupKey = "EMAIL_ONLY:" + parsedRecordId;
                Map<String, Object> payload = new HashMap<>();
                payload.put("parsedRecordId", parsedRecordId.toString());
                payload.put("rawMessageId", record.rawMessageId());
                payload.put("amountPaise", record.amountPaise());
                payload.put("direction", record.direction().name());
                payload.put("channel", record.channel().name());
                payload.put("accountLast4", record.accountLast4());
                payload.put("refNo", record.refNo());
                payload.put("counterpartyRaw", record.counterpartyRaw());
                payload.put("txnTime", record.txnTime().toString());

                review.open(new OpenReview(ReviewType.EMAIL_ONLY, dedupKey, payload, record.rawMessageId(), null));
                return new MatchResult(MatchResult.Outcome.EMAIL_ONLY_REVIEW, null, dedupKey);
            }
        } else if (candidates.size() == 1 && isChannelCompatible(record.channel(), candidates.get(0).channel())) {
            // Exactly 1 compatible candidate -> attach via WINDOW
            TxnCandidate txn = candidates.get(0);
            attachEvidence(txn.id(), parsedRecordId, "WINDOW", 0.9);
            fillNullFields(txn, record, accountId, merchantId);
            events.publishEvent(new TransactionUpdated(txn.id(), parsedRecordId, MatchMethod.WINDOW));

            // R-031-5: Check if refNo was added that resolves pending ambiguous reviews
            if (record.refNo() != null) {
                checkAndResolveAmbiguousMatch(txn.id(), record.refNo());
            }

            return new MatchResult(MatchResult.Outcome.ATTACHED_WINDOW, txn.id(), null);
        } else {
            // Ambiguous match (>1 candidates or channel conflict)
            List<String> candidateIds = candidates.stream().map(c -> c.id().toString()).toList();
            if (record.source() == Source.SMS) {
                // SMS record: new transaction status=PENDING + review AMBIGUOUS_MATCH
                UUID txnId = createTransaction(record, accountId, merchantId, "PENDING");
                attachEvidence(txnId, parsedRecordId, "FIRST", 1.0);
                String dedupKey = "AMBIGUOUS_MATCH:" + txnId;
                Map<String, Object> payload = Map.of(
                        "transactionId", txnId.toString(),
                        "candidateIds", candidateIds,
                        "rawMessageId", record.rawMessageId()
                );
                review.open(new OpenReview(ReviewType.AMBIGUOUS_MATCH, dedupKey, payload, record.rawMessageId(), txnId));
                events.publishEvent(new TransactionCreated(txnId, parsedRecordId));
                return new MatchResult(MatchResult.Outcome.AMBIGUOUS_REVIEW, txnId, dedupKey);
            } else {
                // Email record: no transaction, review AMBIGUOUS_MATCH on the record
                String dedupKey = "AMBIGUOUS_MATCH:" + parsedRecordId;
                Map<String, Object> payload = Map.of(
                        "parsedRecordId", parsedRecordId.toString(),
                        "candidateIds", candidateIds,
                        "rawMessageId", record.rawMessageId()
                );
                review.open(new OpenReview(ReviewType.AMBIGUOUS_MATCH, dedupKey, payload, record.rawMessageId(), null));
                return new MatchResult(MatchResult.Outcome.AMBIGUOUS_REVIEW, null, dedupKey);
            }
        }
    }

    @Override
    @Transactional
    public UUID answerEmailOnlyAdd(UUID parsedRecordId) {
        ParsedRow record = loadParsedRow(parsedRecordId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARSED_RECORD_NOT_FOUND", "Record not found: " + parsedRecordId));

        UUID accountId = resolveAccount(record);
        UUID merchantId = resolveMerchant(record.counterpartyRaw());

        UUID txnId = createTransaction(record, accountId, merchantId, "CONFIRMED");
        attachEvidence(txnId, parsedRecordId, "MANUAL", 1.0);

        review.autoClose("EMAIL_ONLY:" + parsedRecordId);
        events.publishEvent(new TransactionCreated(txnId, parsedRecordId));
        return txnId;
    }

    @Override
    @Transactional
    public void answerEmailOnlyLink(UUID parsedRecordId, UUID transactionId) {
        ParsedRow record = loadParsedRow(parsedRecordId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARSED_RECORD_NOT_FOUND", "Record not found: " + parsedRecordId));
        TxnCandidate txn = findById(transactionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found: " + transactionId));

        UUID accountId = resolveAccount(record);
        UUID merchantId = resolveMerchant(record.counterpartyRaw());

        attachEvidence(transactionId, parsedRecordId, "MANUAL", 1.0);
        fillNullFields(txn, record, accountId, merchantId);

        review.autoClose("EMAIL_ONLY:" + parsedRecordId);
        events.publishEvent(new TransactionUpdated(transactionId, parsedRecordId, MatchMethod.MANUAL));
    }

    @Override
    public Optional<TransactionDetail> findTransaction(UUID transactionId) {
        List<TransactionDetail> list = jdbc.query("""
                SELECT id, amount_paise, direction, txn_time, account_id, merchant_id, kind, status, channel, ref_no
                FROM transaction WHERE id = ?
                """, (rs, rowNum) -> new TransactionDetail(
                rs.getObject("id", UUID.class),
                rs.getLong("amount_paise"),
                Direction.valueOf(rs.getString("direction")),
                rs.getObject("txn_time", OffsetDateTime.class).toInstant(),
                rs.getObject("account_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("ref_no"),
                getEvidence(transactionId)
        ), transactionId);
        return list.stream().findFirst();
    }

    @Override
    public List<TransactionEvidenceView> getEvidence(UUID transactionId) {
        return jdbc.query("""
                SELECT id, transaction_id, parsed_record_id, match_method, score, created_at
                FROM transaction_evidence WHERE transaction_id = ? ORDER BY created_at
                """, (rs, rowNum) -> new TransactionEvidenceView(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("parsed_record_id", UUID.class),
                rs.getString("match_method"),
                rs.getObject("score") != null ? rs.getDouble("score") : null,
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
        ), transactionId);
    }

    @EventListener
    void onAccountMerged(AccountMerged event) {
        jdbc.update("UPDATE transaction SET account_id = ? WHERE account_id = ?",
                event.intoAccountId(), event.fromAccountId());
    }

    @EventListener
    void onMerchantMerged(MerchantMerged event) {
        jdbc.update("UPDATE transaction SET merchant_id = ? WHERE merchant_id = ?",
                event.intoMerchantId(), event.fromMerchantId());
    }

    private void checkAndAttachOpenEmailOnly(UUID txnId, ParsedRow smsRecord, UUID accountId, Duration window) {
        // Find open EMAIL_ONLY review items
        List<UUID> emailRecords = jdbc.query("""
                SELECT pr.id
                FROM review_item r
                JOIN parsed_record pr ON r.raw_message_id = pr.raw_message_id
                WHERE r.type = 'EMAIL_ONLY' AND r.status = 'OPEN'
                  AND pr.amount_paise = ? AND pr.direction = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                smsRecord.amountPaise(), smsRecord.direction().name());

        for (UUID emailRecordId : emailRecords) {
            Optional<ParsedRow> emailRow = loadParsedRow(emailRecordId);
            if (emailRow.isEmpty()) continue;
            ParsedRow er = emailRow.get();

            boolean matchesRef = smsRecord.refNo() != null && smsRecord.refNo().equals(er.refNo());
            boolean matchesWindow = Math.abs(Duration.between(smsRecord.txnTime(), er.txnTime()).toMinutes()) <= window.toMinutes();

            if (matchesRef || matchesWindow) {
                String matchMethod = matchesRef ? "REF" : "WINDOW";
                attachEvidence(txnId, emailRecordId, matchMethod, matchesRef ? 1.0 : 0.9);
                review.autoClose("EMAIL_ONLY:" + emailRecordId);
                // Fill any null fields
                TxnCandidate currentTxn = findById(txnId).orElse(null);
                if (currentTxn != null) {
                    fillNullFields(currentTxn, er, accountId, resolveMerchant(er.counterpartyRaw()));
                }
                events.publishEvent(new TransactionUpdated(txnId, emailRecordId,
                        matchesRef ? MatchMethod.REF : MatchMethod.WINDOW));
            }
        }
    }

    private void checkAndResolveAmbiguousMatch(UUID transactionId, String newRefNo) {
        // If there's an open AMBIGUOUS_MATCH on this transaction, auto-close it
        review.autoClose("AMBIGUOUS_MATCH:" + transactionId);
    }

    private UUID createTransaction(ParsedRow record, UUID accountId, UUID merchantId, String status) {
        UUID id = Ids.newId();
        String kind = record.direction() == Direction.DR ? "SPEND" : "INCOME"; // R-031-6

        jdbc.update("""
                INSERT INTO transaction (id, amount_paise, direction, txn_time, account_id, merchant_id,
                                         category_source, kind, status, channel, ref_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'NONE', ?, ?, ?, ?, now(), now())
                """, id, record.amountPaise(), record.direction().name(),
                OffsetDateTime.ofInstant(record.txnTime(), ZoneOffset.UTC),
                accountId, merchantId, kind, status, record.channel().name(), record.refNo());

        return id;
    }

    private void attachEvidence(UUID transactionId, UUID parsedRecordId, String matchMethod, double score) {
        jdbc.update("""
                INSERT INTO transaction_evidence (id, transaction_id, parsed_record_id, match_method, score, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (parsed_record_id) DO NOTHING
                """, Ids.newId(), transactionId, parsedRecordId, matchMethod, score);
    }

    private void fillNullFields(TxnCandidate txn, ParsedRow evidence, UUID evidenceAccountId, UUID evidenceMerchantId) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (txn.refNo() == null && evidence.refNo() != null) {
            sets.add("ref_no = ?");
            params.add(evidence.refNo());
        }
        if (txn.accountId() == null && evidenceAccountId != null) {
            sets.add("account_id = ?");
            params.add(evidenceAccountId);
        }
        if (txn.merchantId() == null && evidenceMerchantId != null) {
            sets.add("merchant_id = ?");
            params.add(evidenceMerchantId);
        }
        if (txn.channel() == Channel.OTHER && evidence.channel() != Channel.OTHER) {
            sets.add("channel = ?");
            params.add(evidence.channel().name());
        }

        if (!sets.isEmpty()) {
            sets.add("updated_at = now()");
            params.add(txn.id());
            String sql = "UPDATE transaction SET " + String.join(", ", sets) + " WHERE id = ?";
            jdbc.update(sql, params.toArray());
        }
    }

    private boolean isChannelCompatible(Channel c1, Channel c2) {
        return c1 == c2 || c1 == Channel.OTHER || c2 == Channel.OTHER;
    }

    private List<TxnCandidate> findWindowCandidates(ParsedRow record, UUID accountId, Duration window) {
        OffsetDateTime minTime = OffsetDateTime.ofInstant(record.txnTime().minus(window), ZoneOffset.UTC);
        OffsetDateTime maxTime = OffsetDateTime.ofInstant(record.txnTime().plus(window), ZoneOffset.UTC);

        // R-031-2: Candidates must have NO evidence from the same source
        String accountClause = accountId != null ? " AND t.account_id = ?" : " AND t.account_id IS NULL";
        String sql = """
                SELECT t.id, t.amount_paise, t.direction, t.txn_time, t.account_id, t.merchant_id,
                       t.kind, t.status, t.channel, t.ref_no
                FROM transaction t
                WHERE t.amount_paise = ? AND t.direction = ? AND t.status <> 'HIDDEN'"""
                + accountClause + """
                  AND t.txn_time BETWEEN ? AND ?
                  AND NOT EXISTS (
                      SELECT 1 FROM transaction_evidence te
                      JOIN parsed_record pr ON te.parsed_record_id = pr.id
                      JOIN raw_message rm ON pr.raw_message_id = rm.id
                      WHERE te.transaction_id = t.id AND rm.source = ?
                  )
                ORDER BY t.txn_time
                """;

        List<Object> args = new ArrayList<>();
        args.add(record.amountPaise());
        args.add(record.direction().name());
        if (accountId != null) {
            args.add(accountId);
        }
        args.add(minTime);
        args.add(maxTime);
        args.add(record.source().name());

        return jdbc.query(sql, (rs, rowNum) -> new TxnCandidate(
                rs.getObject("id", UUID.class),
                rs.getLong("amount_paise"),
                Direction.valueOf(rs.getString("direction")),
                rs.getObject("txn_time", OffsetDateTime.class).toInstant(),
                rs.getObject("account_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("ref_no")
        ), args.toArray());
    }

    private Optional<TxnCandidate> findByRef(String refNo, Direction direction) {
        List<TxnCandidate> list = jdbc.query("""
                SELECT id, amount_paise, direction, txn_time, account_id, merchant_id, kind, status, channel, ref_no
                FROM transaction
                WHERE ref_no = ? AND direction = ? AND status <> 'HIDDEN'
                """, (rs, rowNum) -> new TxnCandidate(
                rs.getObject("id", UUID.class),
                rs.getLong("amount_paise"),
                Direction.valueOf(rs.getString("direction")),
                rs.getObject("txn_time", OffsetDateTime.class).toInstant(),
                rs.getObject("account_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("ref_no")
        ), refNo, direction.name());
        return list.stream().findFirst();
    }

    private Optional<TxnCandidate> findById(UUID id) {
        List<TxnCandidate> list = jdbc.query("""
                SELECT id, amount_paise, direction, txn_time, account_id, merchant_id, kind, status, channel, ref_no
                FROM transaction WHERE id = ?
                """, (rs, rowNum) -> new TxnCandidate(
                rs.getObject("id", UUID.class),
                rs.getLong("amount_paise"),
                Direction.valueOf(rs.getString("direction")),
                rs.getObject("txn_time", OffsetDateTime.class).toInstant(),
                rs.getObject("account_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("ref_no")
        ), id);
        return list.stream().findFirst();
    }

    private Optional<UUID> findTransactionByEvidence(UUID parsedRecordId) {
        List<UUID> list = jdbc.query(
                "SELECT transaction_id FROM transaction_evidence WHERE parsed_record_id = ?",
                (rs, rowNum) -> rs.getObject("transaction_id", UUID.class), parsedRecordId);
        return list.stream().findFirst();
    }

    private Optional<ParsedRow> loadParsedRow(UUID parsedRecordId) {
        List<ParsedRow> list = jdbc.query("""
                SELECT pr.id, pr.raw_message_id, rm.source, rm.sender, pr.record_type, pr.amount_paise,
                       pr.direction, pr.account_last4, pr.channel, pr.counterparty_raw, pr.ref_no,
                       pr.txn_time, pr.balance_after_paise
                FROM parsed_record pr
                JOIN raw_message rm ON pr.raw_message_id = rm.id
                WHERE pr.id = ?
                """, (rs, rowNum) -> new ParsedRow(
                rs.getObject("id", UUID.class),
                rs.getString("raw_message_id"),
                Source.valueOf(rs.getString("source")),
                rs.getString("sender"),
                rs.getString("record_type"),
                rs.getLong("amount_paise"),
                Direction.valueOf(rs.getString("direction")),
                rs.getString("account_last4"),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("counterparty_raw"),
                rs.getString("ref_no"),
                rs.getObject("txn_time", OffsetDateTime.class).toInstant(),
                rs.getObject("balance_after_paise") != null ? rs.getLong("balance_after_paise") : null
        ), parsedRecordId);
        return list.stream().findFirst();
    }

    private UUID resolveAccount(ParsedRow record) {
        if (record.accountLast4() == null) {
            return null;
        }
        Bank bank = inferBank(record.sender(), record.source());
        if (bank == null) {
            return null;
        }
        return accounts.resolve(bank, record.accountLast4(), AccountType.SAVINGS)
                .map(AccountRef::id)
                .orElse(null);
    }

    private UUID resolveMerchant(String counterpartyRaw) {
        if (counterpartyRaw == null || counterpartyRaw.isBlank()) {
            return null;
        }
        return merchants.resolve(counterpartyRaw)
                .map(MerchantRef::id)
                .orElse(null);
    }

    private Bank inferBank(String sender, Source source) {
        if (sender == null) return null;
        String upper = sender.toUpperCase(Locale.ROOT);
        if (source == Source.SMS) {
            String mid = SenderIds.middlePart(upper).orElse(upper);
            if (mid.contains("AXIS")) return Bank.AXIS;
            if (mid.contains("SBI")) return Bank.SBI;
        } else {
            if (upper.contains("AXIS")) return Bank.AXIS;
            if (upper.contains("SBI")) return Bank.SBI;
        }
        return null;
    }
}
