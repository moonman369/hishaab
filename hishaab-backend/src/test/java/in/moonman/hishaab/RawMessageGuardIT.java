package in.moonman.hishaab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.moonman.hishaab.shared.Ids;
import in.moonman.hishaab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** H-2 enforced by the V1 trigger: content is immutable, rows are never deleted, status may change. */
class RawMessageGuardIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String insert() {
        String id = Ids.sha256Hex("guard-test-" + Ids.newId());
        jdbc.update("INSERT INTO raw_message (id, source, sender, body, received_at) "
                + "VALUES (?, 'SMS', 'AX-AXISBK-S', 'b', now())", id);
        return id;
    }

    @Test
    void statusAndIgnoreReasonMayChange() {
        String id = insert();
        jdbc.update("UPDATE raw_message SET status = 'IGNORED', ignore_reason = 'PROMO' WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT status FROM raw_message WHERE id = ?", String.class, id))
                .isEqualTo("IGNORED");
    }

    @Test
    void bodyCannotBeUpdated() {
        String id = insert();
        assertThatThrownBy(() -> jdbc.update("UPDATE raw_message SET body = 'changed' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void rowsCannotBeDeleted() {
        String id = insert();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM raw_message WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void unknownStatusRejected() {
        String id = insert();
        assertThatThrownBy(() -> jdbc.update("UPDATE raw_message SET status = 'BOGUS' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }
}
