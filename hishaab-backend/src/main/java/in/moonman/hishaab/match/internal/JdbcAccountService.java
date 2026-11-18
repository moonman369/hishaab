package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.AccountMerged;
import in.moonman.hishaab.match.AccountService;
import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.Ids;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcAccountService implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(JdbcAccountService.class);
    private static final Pattern FOUR_DIGITS = Pattern.compile("^\\d{4}$");

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    JdbcAccountService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public Optional<AccountRef> resolve(Bank bank, String last4, AccountType typeIfNew) {
        if (bank == null || last4 == null || !FOUR_DIGITS.matcher(last4).matches()) {
            return Optional.empty();
        }
        Optional<AccountRef> existing = find(bank, last4);
        if (existing.isPresent()) {
            return existing;
        }
        AccountType type = typeIfNew == null ? AccountType.SAVINGS : typeIfNew;
        UUID id = Ids.newId();
        // ON CONFLICT covers two resolvers racing on the same new number. If an alias for this number is added at the
        // same instant, the DB trigger fails this transaction; the caller's message ends ERROR and can be reprocessed.
        int inserted = jdbc.update("""
                        INSERT INTO account (id, bank, type, last4, nickname) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (bank, last4) DO NOTHING""",
                id, bank.name(), type.name(), last4, defaultNickname(bank, type, last4));
        if (inserted == 1) {
            log.info("event=account_auto_created accountId={} bank={} type={}", id, bank, type);
            return Optional.of(new AccountRef(id, bank, type, defaultNickname(bank, type, last4), true));
        }
        return find(bank, last4);
    }

    /** R-030-5: the account's own number first, then aliases. */
    private Optional<AccountRef> find(Bank bank, String last4) {
        List<AccountRef> own = jdbc.query("SELECT id, bank, type, nickname FROM account WHERE bank = ? AND last4 = ?",
                (rs, n) -> ref(rs.getObject("id", UUID.class), rs.getString("bank"), rs.getString("type"),
                        rs.getString("nickname")), bank.name(), last4);
        if (!own.isEmpty()) {
            return Optional.of(own.getFirst());
        }
        List<AccountRef> viaAlias = jdbc.query("""
                        SELECT a.id, a.bank, a.type, a.nickname FROM account_alias x JOIN account a ON a.id = x.account_id
                        WHERE x.bank = ? AND x.last4 = ?""",
                (rs, n) -> ref(rs.getObject("id", UUID.class), rs.getString("bank"), rs.getString("type"),
                        rs.getString("nickname")), bank.name(), last4);
        return viaAlias.stream().findFirst();
    }

    private static AccountRef ref(UUID id, String bank, String type, String nickname) {
        return new AccountRef(id, Bank.valueOf(bank), AccountType.valueOf(type), nickname, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        Map<UUID, List<AliasView>> aliases = jdbc.query(
                        "SELECT id, account_id, last4, kind FROM account_alias ORDER BY last4",
                        (rs, n) -> Map.entry(rs.getObject("account_id", UUID.class),
                                new AliasView(rs.getObject("id", UUID.class), rs.getString("last4"),
                                        AliasKind.valueOf(rs.getString("kind")))))
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.query("SELECT * FROM account ORDER BY bank, type, last4", (rs, n) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new AccountView(id, Bank.valueOf(rs.getString("bank")), AccountType.valueOf(rs.getString("type")),
                    rs.getString("last4"), rs.getString("nickname"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(), aliases.getOrDefault(id, List.of()));
        });
    }

    @Override
    @Transactional
    public AccountView rename(UUID accountId, String nickname) {
        if (jdbc.update("UPDATE account SET nickname = ? WHERE id = ?", nickname.strip(), accountId) == 0) {
            throw notFound(accountId);
        }
        return view(accountId);
    }

    @Override
    @Transactional
    public AccountView addAlias(UUID accountId, String last4, AliasKind kind) {
        String number = last4 == null ? "" : last4.strip();
        if (!FOUR_DIGITS.matcher(number).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALIAS", "last4 must be exactly 4 digits");
        }
        String bank = bankOf(accountId);
        if (taken(bank, number)) {
            throw aliasConflict(bank, number);
        }
        try {
            jdbc.update("INSERT INTO account_alias (id, account_id, bank, last4, kind) VALUES (?, ?, ?, ?, ?)",
                    Ids.newId(), accountId, bank, number, (kind == null ? AliasKind.OTHER : kind).name());
        } catch (DataIntegrityViolationException e) {
            throw aliasConflict(bank, number); // lost a race; the DB trigger/unique index is the final word
        }
        log.info("event=account_alias_added accountId={}", accountId);
        return view(accountId);
    }

    @Override
    @Transactional
    public void removeAlias(UUID accountId, UUID aliasId) {
        if (jdbc.update("DELETE FROM account_alias WHERE id = ? AND account_id = ?", aliasId, accountId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ALIAS_NOT_FOUND", "alias " + aliasId + " not found");
        }
    }

    @Override
    @Transactional
    public AccountView merge(UUID fromId, UUID intoId, AliasKind kindForOldNumber) {
        if (fromId.equals(intoId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MERGE_SELF", "cannot merge an account into itself");
        }
        Map<String, Object> from = row(fromId);
        Map<String, Object> into = row(intoId);
        if (!from.get("bank").equals(into.get("bank")) || !from.get("type").equals(into.get("type"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_MERGE_MISMATCH",
                    "only accounts of the same bank and type can be merged");
        }
        jdbc.update("UPDATE account_alias SET account_id = ? WHERE account_id = ?", intoId, fromId);
        events.publishEvent(new AccountMerged(fromId, intoId)); // SPEC-031 repoints transactions here
        jdbc.update("DELETE FROM account WHERE id = ?", fromId);
        jdbc.update("INSERT INTO account_alias (id, account_id, bank, last4, kind) VALUES (?, ?, ?, ?, ?)",
                Ids.newId(), intoId, from.get("bank"), from.get("last4"),
                (kindForOldNumber == null ? AliasKind.OTHER : kindForOldNumber).name());
        log.info("event=account_merged from={} into={}", fromId, intoId);
        return view(intoId);
    }

    private boolean taken(String bank, String last4) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM account WHERE bank = ? AND last4 = ?)
                    OR EXISTS (SELECT 1 FROM account_alias WHERE bank = ? AND last4 = ?)""",
                Boolean.class, bank, last4, bank, last4));
    }

    private String bankOf(UUID accountId) {
        return (String) row(accountId).get("bank");
    }

    private Map<String, Object> row(UUID accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM account WHERE id = ?", accountId);
        if (rows.isEmpty()) {
            throw notFound(accountId);
        }
        return rows.getFirst();
    }

    private AccountView view(UUID accountId) {
        return list().stream().filter(a -> a.id().equals(accountId)).findFirst().orElseThrow(() -> notFound(accountId));
    }

    static String defaultNickname(Bank bank, AccountType type, String last4) {
        String label = bank == Bank.AXIS ? "Axis" : "SBI";
        return label + (type == AccountType.CREDIT_CARD ? " card ••" : " ••") + last4;
    }

    private static ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "account " + id + " not found");
    }

    private static ApiException aliasConflict(String bank, String last4) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ALIAS_CONFLICT",
                bank + " ••" + last4 + " already belongs to an account");
    }
}
