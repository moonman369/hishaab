package in.moonman.hishaab.match;

import java.util.UUID;

/**
 * Published inside the merge transaction, before {@code from} is deleted. Listeners that reference merchants
 * (transactions, SPEC-031; category rules, SPEC-043) must repoint synchronously in the same transaction.
 */
public record MerchantMerged(UUID fromMerchantId, UUID intoMerchantId) {
}
