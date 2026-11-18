package in.moonman.hishaab.match;

import java.util.UUID;

/**
 * Published inside the merge transaction, before {@code from} is deleted. Listeners that reference accounts
 * (transactions, SPEC-031; investments, SPEC-045) must repoint synchronously in the same transaction.
 */
public record AccountMerged(UUID fromAccountId, UUID intoAccountId) {
}
