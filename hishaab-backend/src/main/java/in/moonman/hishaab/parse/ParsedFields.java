package in.moonman.hishaab.parse;

import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import java.time.Instant;
import java.util.Objects;

/**
 * Normalised fields of one message (SPEC-014). Money is paise (H-1).
 *
 * @param accountLast4      exactly 4 digits, or null
 * @param counterpartyRaw   cleaned but original case, or null
 * @param refNo             6–22 digits, or null
 * @param balanceAfterPaise balance printed in the message, or null
 */
public record ParsedFields(long amountPaise, Direction direction, String accountLast4, Channel channel,
                           String counterpartyRaw, String refNo, Instant txnTime, Long balanceAfterPaise) {

    public ParsedFields {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(txnTime, "txnTime");
    }
}
