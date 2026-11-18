package in.moonman.hishaab.parse.internal;

import in.moonman.hishaab.parse.ParsedFields;
import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.parse.ParsedRecords;
import in.moonman.hishaab.shared.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcParsedRecords implements ParsedRecords {

    private final JdbcTemplate jdbc;

    JdbcParsedRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UUID save(String rawMessageId, ParsedMessage m) {
        UUID id = Ids.newId();
        ParsedFields f = m.fields();
        jdbc.update("""
                        INSERT INTO parsed_record (id, raw_message_id, template_id, template_version, parser, record_type,
                          amount_paise, direction, account_last4, channel, counterparty_raw, ref_no, txn_time,
                          balance_after_paise, confidence)
                        VALUES (?, ?, ?, ?, 'TEMPLATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, rawMessageId, m.templateId(), m.templateVersion(), m.recordType().name(), f.amountPaise(),
                f.direction().name(), f.accountLast4(), f.channel().name(), f.counterpartyRaw(), f.refNo(),
                OffsetDateTime.ofInstant(f.txnTime(), ZoneOffset.UTC), f.balanceAfterPaise(),
                BigDecimal.valueOf(m.confidence()));
        return id;
    }

    @Override
    @Transactional
    public int deleteForRawMessage(String rawMessageId) {
        return jdbc.update("DELETE FROM parsed_record WHERE raw_message_id = ?", rawMessageId);
    }

    @Override
    @Transactional
    public int deleteAll() {
        return jdbc.update("DELETE FROM parsed_record");
    }
}
