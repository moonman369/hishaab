package in.moonman.hishaab.review.internal;

import in.moonman.hishaab.review.ReviewService;
import in.moonman.hishaab.shared.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class JdbcReviewService implements ReviewService {

    private static final Logger log = LoggerFactory.getLogger(JdbcReviewService.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    JdbcReviewService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public boolean open(OpenReview r) {
        int rows = jdbc.update("""
                        INSERT INTO review_item (id, type, status, payload, dedup_key, raw_message_id, transaction_id)
                        VALUES (?, ?, 'OPEN', ?::jsonb, ?, ?, ?)
                        ON CONFLICT (dedup_key) DO NOTHING""",
                Ids.newId(), r.type().name(), json.writeValueAsString(r.payload()), r.dedupKey(), r.rawMessageId(),
                r.transactionId());
        if (rows == 1) {
            log.info("event=review_opened type={} dedupKey={}", r.type(), r.dedupKey());
        }
        return rows == 1;
    }

    @Override
    @Transactional
    public int deleteOpenForRawMessage(String rawMessageId) {
        return jdbc.update("DELETE FROM review_item WHERE status = 'OPEN' AND raw_message_id = ?", rawMessageId);
    }

    @Override
    @Transactional
    public int deleteOpenForAllRawMessages() {
        return jdbc.update("DELETE FROM review_item WHERE status = 'OPEN' AND raw_message_id IS NOT NULL");
    }
}
