package in.moonman.hishaab.match.internal;

import in.moonman.hishaab.match.AccountMerged;
import in.moonman.hishaab.match.AccountService;
import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcAccountService implements AccountService {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    JdbcAccountService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public Optional<AccountRef> resolve(Bank bank, String last4, AccountType typeIfNew) {
        if (bank == null || last4 == null || !last4.matches("^[0-9]{4}$")) {
            return Optional.empty();
        }

        // 1. Direct match on account's own last4
        List<AccountRef> direct = jdbc.query(
                "SELECT id, bank, type, nickname FROM account WHERE bank = ? AND last4 = ?",
                (rs, rowNum) -> new AccountRef(
                        rs.getObject("id", UUID.class),
                        Bank.valueOf(rs.getString("bank")),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getString("nickname"),
                        false
                ), bank.name(), last4);
        if (!direct.isEmpty()) {
            return Optional.of(direct.get(0));
        }

        // 2. Card alias match
        List<AccountRef> viaAlias = jdbc.query("""
                SELECT a.id, a.bank, a.type, a.nickname
                FROM account_alias x
                JOIN account a ON x.account_id = a.id
                WHERE x.bank = ? AND x.last4 = ?
                """, (rs, rowNum) -> new AccountRef(
                rs.getObject("id", UUID.class),
                Bank.valueOf(rs.getString("bank")),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("nickname"),
                false
        ), bank.name(), last4);
        if (!viaAlias.isEmpty()) {
            return Optional.of(viaAlias.get(0));
        }

        // 3. Auto-create if type is known (R-030-1)
        if (typeIfNew == null) {
            return Optional.empty();
        }

        UUID accountId = Ids.newId();
        String bankDisplay = bank == Bank.AXIS ? "Axis" : "SBI";
        String nickname = typeIfNew == AccountType.CREDIT_CARD
                ? (bankDisplay + " card ••" + last4)
                : (bankDisplay + " ••" + last4);

        jdbc.update("INSERT INTO account (id, bank, type, last4, nickname) VALUES (?, ?, ?, ?, ?)",
                accountId, bank.name(), typeIfNew.name(), last4, nickname);

        return Optional.of(new AccountRef(accountId, bank, typeIfNew, nickname, true));
    }

    @Override
    public List<AccountView> list() {
        return jdbc.query("SELECT id, bank, type, last4, nickname, created_at FROM account ORDER BY bank, nickname",
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new AccountView(
                            id,
                            Bank.valueOf(rs.getString("bank")),
                            AccountType.valueOf(rs.getString("type")),
                            rs.getString("last4"),
                            rs.getString("nickname"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            loadAliases(id)
                    );
                });
    }

    @Override
    @Transactional
    public AccountView rename(UUID accountId, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NICKNAME", "Nickname must not be blank");
        }
        AccountView current = findView(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + accountId));

        jdbc.update("UPDATE account SET nickname = ? WHERE id = ?", nickname.trim(), accountId);
        return findView(accountId).orElseThrow();
    }

    @Override
    @Transactional
    public AccountView addAlias(UUID accountId, String last4, AliasKind kind) {
        if (last4 == null || !last4.matches("^[0-9]{4}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LAST4", "last4 must be exactly 4 digits");
        }
        if (kind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALIAS_KIND", "kind must not be null");
        }

        AccountView account = findView(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + accountId));

        try {
            jdbc.update("INSERT INTO account_alias (id, account_id, bank, last4, kind) VALUES (?, ?, ?, ?, ?)",
                    Ids.newId(), accountId, account.bank().name(), last4, kind.name());
        } catch (DataAccessException ex) {
            String msg = ex.getMessage();
            if (msg != null && (msg.contains("ALIAS_CONFLICT") || msg.contains("account_alias_bank_last4_key") || msg.contains("account_bank_last4_key"))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ALIAS_CONFLICT", "Alias conflict for " + account.bank() + " " + last4);
            }
            throw ex;
        }

        return findView(accountId).orElseThrow();
    }

    @Override
    @Transactional
    public void removeAlias(UUID accountId, UUID aliasId) {
        jdbc.update("DELETE FROM account_alias WHERE id = ? AND account_id = ?", aliasId, accountId);
    }

    @Override
    @Transactional
    public AccountView merge(UUID fromAccountId, UUID intoAccountId, AliasKind kindForOldNumber) {
        AccountView from = findView(fromAccountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + fromAccountId));
        AccountView into = findView(intoAccountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + intoAccountId));

        if (fromAccountId.equals(intoAccountId)) {
            return into;
        }

        if (from.bank() != into.bank() || from.type() != into.type()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_MERGE_MISMATCH",
                    "Cannot merge accounts of different bank or type (" + from.bank() + " " + from.type()
                            + " -> " + into.bank() + " " + into.type() + ")");
        }

        AliasKind aliasKind = kindForOldNumber != null ? kindForOldNumber : AliasKind.DEBIT_CARD;

        // R-030-6: from's aliases move to into
        jdbc.update("UPDATE account_alias SET account_id = ? WHERE account_id = ?", intoAccountId, fromAccountId);

        // Repoint transactions synchronously inside this transaction
        events.publishEvent(new AccountMerged(fromAccountId, intoAccountId));

        // Delete from account so its last4 can become an alias without trigger conflict
        jdbc.update("DELETE FROM account WHERE id = ?", fromAccountId);

        // Turn from's last4 into an alias of into
        jdbc.update("INSERT INTO account_alias (id, account_id, bank, last4, kind) VALUES (?, ?, ?, ?, ?)",
                Ids.newId(), intoAccountId, into.bank().name(), from.last4(), aliasKind.name());

        return findView(intoAccountId).orElseThrow();
    }

    private Optional<AccountView> findView(UUID accountId) {
        List<AccountView> list = jdbc.query(
                "SELECT id, bank, type, last4, nickname, created_at FROM account WHERE id = ?",
                (rs, rowNum) -> new AccountView(
                        rs.getObject("id", UUID.class),
                        Bank.valueOf(rs.getString("bank")),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getString("last4"),
                        rs.getString("nickname"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        loadAliases(accountId)
                ), accountId);
        return list.stream().findFirst();
    }

    private List<AliasView> loadAliases(UUID accountId) {
        return jdbc.query("SELECT id, last4, kind FROM account_alias WHERE account_id = ? ORDER BY created_at",
                (rs, rowNum) -> new AliasView(
                        rs.getObject("id", UUID.class),
                        rs.getString("last4"),
                        AliasKind.valueOf(rs.getString("kind"))
                ), accountId);
    }
}
