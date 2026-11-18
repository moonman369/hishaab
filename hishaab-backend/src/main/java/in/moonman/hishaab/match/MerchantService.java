package in.moonman.hishaab.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public API for merchants and people you pay (SPEC-030). Not derived data: rebuilds never delete them. */
public interface MerchantService {

    /**
     * R-030-2 / R-030-3: normalises the raw counterparty and returns its merchant, creating one (with a guessed
     * {@link MerchantType}) the first time. Empty when nothing usable is left after normalisation.
     */
    Optional<MerchantRef> resolve(String counterpartyRaw);

    /** Case-insensitive search on name and aliases; empty {@code q} lists by name. */
    List<MerchantView> search(String q, int limit);

    /** Any argument may be null (unchanged). You fix wrong PERSON/MERCHANT guesses here. */
    MerchantView update(UUID merchantId, String name, MerchantType type, Boolean alwaysAsk);

    /** R-030-4: aliases move to {@code into}, {@link MerchantMerged} is published, {@code from} is deleted. */
    MerchantView merge(UUID fromMerchantId, UUID intoMerchantId);

    enum MerchantType { MERCHANT, PERSON }

    record MerchantRef(UUID id, String name, MerchantType type, boolean created) {
    }

    record MerchantView(UUID id, String name, MerchantType type, boolean alwaysAsk, List<String> aliases) {
    }
}
