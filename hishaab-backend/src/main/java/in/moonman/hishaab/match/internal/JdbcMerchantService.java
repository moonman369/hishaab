package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.MerchantMerged;
import in.moonman.hishaab.match.MerchantService;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.Ids;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcMerchantService implements MerchantService {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    JdbcMerchantService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public Optional<MerchantRef> resolve(String counterpartyRaw) {
        if (counterpartyRaw == null || counterpartyRaw.isBlank()) {
            return Optional.empty();
        }

        String normalised = MerchantNormaliser.normalise(counterpartyRaw);
        if (normalised.isBlank()) {
            return Optional.empty();
        }

        List<MerchantRef> existing = jdbc.query("""
                SELECT m.id, m.name, m.type
                FROM merchant_alias a
                JOIN merchant m ON a.merchant_id = m.id
                WHERE a.raw_name_normalised = ?
                """, (rs, rowNum) -> new MerchantRef(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                MerchantType.valueOf(rs.getString("type")),
                false
        ), normalised);

        if (!existing.isEmpty()) {
            return Optional.of(existing.get(0));
        }

        // Create new merchant
        MerchantType type = MerchantNormaliser.classifyType(counterpartyRaw);
        UUID merchantId = Ids.newId();
        String merchantName = counterpartyRaw.trim();

        jdbc.update("INSERT INTO merchant (id, name, type) VALUES (?, ?, ?)",
                merchantId, merchantName, type.name());

        jdbc.update("INSERT INTO merchant_alias (id, merchant_id, raw_name, raw_name_normalised) VALUES (?, ?, ?, ?)",
                Ids.newId(), merchantId, counterpartyRaw.trim(), normalised);

        return Optional.of(new MerchantRef(merchantId, merchantName, type, true));
    }

    @Override
    public List<MerchantView> search(String q, int limit) {
        int max = limit <= 0 ? 50 : Math.min(limit, 200);
        String sql;
        Object[] args;

        if (q == null || q.isBlank()) {
            sql = "SELECT id, name, type, always_ask FROM merchant ORDER BY name LIMIT ?";
            args = new Object[]{max};
        } else {
            String pattern = "%" + q.trim() + "%";
            sql = """
                    SELECT m.id, m.name, m.type, m.always_ask
                    FROM merchant m
                    WHERE m.name ILIKE ? OR EXISTS (
                        SELECT 1 FROM merchant_alias a WHERE a.merchant_id = m.id AND a.raw_name ILIKE ?
                    )
                    ORDER BY m.name LIMIT ?
                    """;
            args = new Object[]{pattern, pattern, max};
        }

        return jdbc.query(sql, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            List<String> aliases = loadAliases(id);
            return new MerchantView(
                    id,
                    rs.getString("name"),
                    MerchantType.valueOf(rs.getString("type")),
                    rs.getBoolean("always_ask"),
                    aliases
            );
        }, args);
    }

    @Override
    @Transactional
    public MerchantView update(UUID merchantId, String name, MerchantType type, Boolean alwaysAsk) {
        MerchantView current = findById(merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found: " + merchantId));

        String newName = name != null && !name.isBlank() ? name.trim() : current.name();
        MerchantType newType = type != null ? type : current.type();
        boolean newAlwaysAsk = alwaysAsk != null ? alwaysAsk : current.alwaysAsk();

        jdbc.update("UPDATE merchant SET name = ?, type = ?, always_ask = ? WHERE id = ?",
                newName, newType.name(), newAlwaysAsk, merchantId);

        return new MerchantView(merchantId, newName, newType, newAlwaysAsk, current.aliases());
    }

    @Override
    @Transactional
    public MerchantView merge(UUID fromMerchantId, UUID intoMerchantId) {
        MerchantView from = findById(fromMerchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found: " + fromMerchantId));
        MerchantView into = findById(intoMerchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found: " + intoMerchantId));

        if (fromMerchantId.equals(intoMerchantId)) {
            return into;
        }

        // R-030-4: aliases move to into, MerchantMerged is published, from is deleted.
        jdbc.update("UPDATE merchant_alias SET merchant_id = ? WHERE merchant_id = ?", intoMerchantId, fromMerchantId);

        events.publishEvent(new MerchantMerged(fromMerchantId, intoMerchantId));

        jdbc.update("DELETE FROM merchant WHERE id = ?", fromMerchantId);

        return findById(intoMerchantId).orElseThrow();
    }

    private Optional<MerchantView> findById(UUID id) {
        List<MerchantView> list = jdbc.query(
                "SELECT id, name, type, always_ask FROM merchant WHERE id = ?",
                (rs, rowNum) -> new MerchantView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        MerchantType.valueOf(rs.getString("type")),
                        rs.getBoolean("always_ask"),
                        loadAliases(id)
                ), id);
        return list.stream().findFirst();
    }

    private List<String> loadAliases(UUID merchantId) {
        return jdbc.query(
                "SELECT raw_name_normalised FROM merchant_alias WHERE merchant_id = ? ORDER BY created_at",
                (rs, rowNum) -> rs.getString("raw_name_normalised"),
                merchantId
        );
    }
}
