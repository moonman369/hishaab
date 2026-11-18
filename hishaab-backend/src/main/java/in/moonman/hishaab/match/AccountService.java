package in.moonman.hishaab.match;

import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public API for accounts (SPEC-030). Accounts are not derived data: rebuilds never delete them. */
public interface AccountService {

    /**
     * R-030-1 / R-030-5: resolves {@code (bank, last4)} via the account's own number, then card aliases, else
     * auto-creates an account of {@code typeIfNew}. Empty when bank or last4 is unknown (e.g. fund-house records).
     */
    Optional<AccountRef> resolve(Bank bank, String last4, AccountType typeIfNew);

    List<AccountView> list();

    AccountView rename(UUID accountId, String nickname);

    /** @throws in.moonman.hishaab.shared.ApiException 400 {@code ALIAS_CONFLICT} if the number is taken (AC-030-6) */
    AccountView addAlias(UUID accountId, String last4, AliasKind kind);

    void removeAlias(UUID accountId, UUID aliasId);

    /**
     * R-030-6: merges {@code from} into {@code into} (same bank and type). {@code from}'s aliases move,
     * its own number becomes an alias of {@code into}, {@link AccountMerged} is published (transactions repoint,
     * SPEC-031) and {@code from} is deleted.
     */
    AccountView merge(UUID fromAccountId, UUID intoAccountId, AliasKind kindForOldNumber);

    enum AliasKind { DEBIT_CARD, CREDIT_CARD, OTHER }

    /** @param created true when this call auto-created the account */
    record AccountRef(UUID id, Bank bank, AccountType type, String nickname, boolean created) {
    }

    record AccountView(UUID id, Bank bank, AccountType type, String last4, String nickname, Instant createdAt,
                       List<AliasView> aliases) {
    }

    record AliasView(UUID id, String last4, AliasKind kind) {
    }
}
