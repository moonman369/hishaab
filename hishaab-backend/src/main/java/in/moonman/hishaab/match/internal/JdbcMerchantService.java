package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.MerchantMerged;
import in.moonman.hishaab.match.MerchantService;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.Ids;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcMerchantService implements MerchantService {

    private static final Logger log = LoggerFactory.getLogger(JdbcMerchantService.class);

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    JdbcMerchantService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public Optional<MerchantRef> resolve(String counterpartyRaw) {
        Optional<MerchantNames.Normalised> normalised = MerchantNames.of(counterpartyRaw);
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        MerchantNames.Normalised n = normalised.get();
        Optional<MerchantRef> existing = findByKey(n.key());
        if (existing.isPresent()) {
            return existing;
        }
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO merchant (id, name, type) VALUES (?, ?, ?)", id, n.displayName(), n.type().name());
        int aliased = jdbc.update("""
                        INSERT INTO merchant_alias (id, merchant_id, raw_name, raw_name_normalised) VALUES (?, ?, ?, ?)
                        ON CONFLICT (raw_name_normalised) DO NOTHING""",
                Ids.newId(), id, counterpartyRaw.strip(), n.key());
        if (aliased == 0) { // created concurrently by someone else: use theirs
            jdbc.update("DELETE FROM merchant WHERE id = ?", id);
            return findByKey(n.key());
        }
        log.info("event=merchant_created merchantId={} type={}", id, n.type());
        return Optional.of(new MerchantRef(id, n.displayName(), n.type(), true));
    }

    private Optional<MerchantRef> findByKey(String key) {
        return jdbc.query("""
                        SELECT m.id, m.name, m.type FROM merchant_alias a JOIN merchant m ON m.id = a.merchant_id
                        WHERE a.raw_name_normalised = ?""",
                (rs, i) -> new MerchantRef(rs.getObject("id", UUID.class), rs.getString("name"),
                        MerchantType.valueOf(rs.getString("type")), false), key).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantView> search(String q, int limit) {
        String pattern = "%" + (q == null ? "" : q.strip().replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_")) + "%";
        List<UUID> ids = jdbc.queryForList("""
                        SELECT m.id FROM merchant m
                        WHERE m.name ILIKE ? OR EXISTS (SELECT 1 FROM merchant_alias a
                                                       WHERE a.merchant_id = m.id AND a.raw_name ILIKE ?)
                        ORDER BY m.name, m.id LIMIT ?""",
                UUID.class, pattern, pattern, limit);
        return ids.stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public MerchantView update(UUID merchantId, String name, MerchantType type, Boolean alwaysAsk) {
        ensureExists(merchantId);
        if (name != null) {
            jdbc.update("UPDATE merchant SET name = ? WHERE id = ?", name.strip(), merchantId);
        }
        if (type != null) {
            jdbc.update("UPDATE merchant SET type = ? WHERE id = ?", type.name(), merchantId);
        }
        if (alwaysAsk != null) {
            jdbc.update("UPDATE merchant SET always_ask = ? WHERE id = ?", alwaysAsk, merchantId);
        }
        return view(merchantId);
    }

    @Override
    @Transactional
    public MerchantView merge(UUID fromId, UUID intoId) {
        if (fromId.equals(intoId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MERGE_SELF", "cannot merge a merchant into itself");
        }
        ensureExists(fromId);
        ensureExists(intoId);
        jdbc.update("UPDATE merchant_alias SET merchant_id = ? WHERE merchant_id = ?", intoId, fromId);
        events.publishEvent(new MerchantMerged(fromId, intoId)); // SPEC-031 repoints transactions here
        jdbc.update("DELETE FROM merchant WHERE id = ?", fromId);
        log.info("event=merchant_merged from={} into={}", fromId, intoId);
        return view(intoId);
    }

    private void ensureExists(UUID id) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM merchant WHERE id = ?)", Boolean.class, id);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "merchant " + id + " not found");
        }
    }

    private MerchantView view(UUID id) {
        List<String> aliases = jdbc.queryForList(
                "SELECT raw_name FROM merchant_alias WHERE merchant_id = ? ORDER BY created_at, raw_name", String.class, id);
        return jdbc.queryForObject("SELECT id, name, type, always_ask FROM merchant WHERE id = ?",
                (rs, i) -> new MerchantView(rs.getObject("id", UUID.class), rs.getString("name"),
                        MerchantType.valueOf(rs.getString("type")), rs.getBoolean("always_ask"), aliases), id);
    }
}
