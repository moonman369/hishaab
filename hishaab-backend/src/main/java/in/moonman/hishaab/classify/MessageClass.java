package in.moonman.hishaab.classify;

import java.util.List;

/** What a raw message is (SPEC §4.3). */
public enum MessageClass {
    TRANSACTION,
    /** A fund-house (AMC) SIP allotment: proves an instalment, never a transaction of its own (HLD D-24). */
    INVESTMENT_CONFIRMATION,
    OTP, PROMO, MANDATE_REMINDER, FAILED_TXN, BALANCE_ONLY, STATEMENT, UNKNOWN;

    /** R-012-2: the order classes are checked in. First match wins; {@link #UNKNOWN} is the fallback. */
    public static final List<MessageClass> CHECK_ORDER = List.of(OTP, INVESTMENT_CONFIRMATION, PROMO, FAILED_TXN,
            MANDATE_REMINDER, BALANCE_ONLY, STATEMENT, TRANSACTION);
}
