package in.moonman.hishaab.classify;

import in.moonman.hishaab.shared.RawStatus;
import java.util.Objects;

/**
 * Result of classifying one message (R-012-1): the class plus a short reason naming the rule that matched.
 * {@link #rawStatus()} and {@link #ignoreReason()} encode R-012-4; the orchestrator (SPEC-015) applies them.
 */
public record Classification(MessageClass messageClass, String reason) {

    public Classification {
        Objects.requireNonNull(messageClass, "messageClass");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * R-012-4: {@code TRANSACTION} and {@code UNKNOWN} → {@code CLASSIFIED} (processing continues);
     * {@code FAILED_TXN} → {@code FAILED}; everything else → {@code IGNORED}.
     */
    public RawStatus rawStatus() {
        return switch (messageClass) {
            case TRANSACTION, UNKNOWN -> RawStatus.CLASSIFIED;
            case FAILED_TXN -> RawStatus.FAILED;
            case OTP, PROMO, MANDATE_REMINDER, BALANCE_ONLY, STATEMENT -> RawStatus.IGNORED;
        };
    }

    /** The class name for messages that stop here ({@code IGNORED} or {@code FAILED}); null otherwise. */
    public String ignoreReason() {
        return rawStatus() == RawStatus.CLASSIFIED ? null : messageClass.name();
    }
}
