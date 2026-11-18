package in.moonman.hishaab.parse.internal;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * R-013-1 / AC-013-3: at startup, upserts every loaded template into {@code parse_template} by {@code (id, version)}
 * and fails startup if a stored version's hash differs (a regex edited without bumping {@code version}). Versions
 * no longer in the YAML stay in the table for audit, marked inactive.
 */
@Component
class TemplateRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TemplateRegistry.class);

    private final TemplateEngine engine;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    TemplateRegistry(TemplateEngine engine, JdbcTemplate jdbc, JsonMapper json, TransactionTemplate tx) {
        this.engine = engine;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        sync(engine.templates());
    }

    void sync(List<ParseTemplate> templates) {
        tx.executeWithoutResult(status -> {
            for (ParseTemplate t : templates) {
                String hash = t.regexHash();
                List<String> stored = jdbc.queryForList(
                        "SELECT regex_hash FROM parse_template WHERE id = ? AND version = ?", String.class,
                        t.id(), t.version());
                if (stored.isEmpty()) {
                    jdbc.update("""
                                    INSERT INTO parse_template (id, version, bank, source, sender_pattern, regex,
                                      regex_hash, fixed, account_type, record_type, active)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, true)""",
                            t.id(), t.version(), t.bank() == null ? null : t.bank().name(), t.source().name(),
                            t.senderPatternText(), t.regexText(), hash, json.writeValueAsString(t.fixed()),
                            t.accountType() == null ? null : t.accountType().name(), t.recordType().name());
                    log.info("event=parse_template_registered id={} version={}", t.id(), t.version());
                } else if (!stored.getFirst().equals(hash)) {
                    throw new IllegalStateException("parse template " + t.id() + " v" + t.version()
                            + " was changed without bumping its version (AC-013-3). Add a new version instead.");
                }
            }
            jdbc.update("UPDATE parse_template SET active = false");
            for (ParseTemplate t : templates) {
                jdbc.update("UPDATE parse_template SET active = true WHERE id = ? AND version = ?", t.id(), t.version());
            }
        });
    }
}
