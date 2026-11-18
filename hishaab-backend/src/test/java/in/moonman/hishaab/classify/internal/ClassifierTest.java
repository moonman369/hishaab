package in.moonman.hishaab.classify.internal;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.classify.Classification;
import in.moonman.hishaab.classify.MessageClass;
import in.moonman.hishaab.shared.RawStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * SPEC-012. Bodies are illustrative (synthetic); AC-012-6 re-checks against real masked samples (SPEC-013).
 * The "safety" cases protect totals: a real transaction must never be silently ignored.
 */
class ClassifierTest {

    private static final RuleBasedClassifier CLASSIFIER = new RuleBasedClassifier();

    private static MessageClass classOf(String body) {
        return CLASSIFIER.classify("AX-AXISBK-S", body).messageClass();
    }

    @Test
    void AC_012_01_otpWinsEvenWithAnAmount() {
        assertThat(classOf("123456 is your OTP for txn of Rs 500 at AMAZON. Valid for 10 mins. Do not share it."))
                .isEqualTo(MessageClass.OTP);
    }

    @Test
    void AC_012_02_futureDebitIsMandateReminder() {
        assertThat(classOf("Rs 499 will be debited on 20-11 for your mandate towards NETFLIX. UMN 1234@axis"))
                .isEqualTo(MessageClass.MANDATE_REMINDER);
    }

    @Test
    void AC_012_03_failedOrDeclinedIsFailedTxn() {
        assertThat(classOf("Txn of Rs 250 failed/declined at SWIGGY due to insufficient balance."))
                .isEqualTo(MessageClass.FAILED_TXN);
    }

    @Test
    void AC_012_04_balanceWithoutMovementIsBalanceOnly() {
        assertThat(classOf("Avl Bal in A/c XX1234 is Rs 10,000.00 as on 17-11-26."))
                .isEqualTo(MessageClass.BALANCE_ONLY);
    }

    @Test
    void AC_012_05_amountAndDebitIsTransaction() {
        Classification c = CLASSIFIER.classify("AX-AXISBK-S",
                "Rs 450.00 debited from A/c XX1234 on 17-11-26 to VPA swiggy@axis. UPI Ref 432112345678. Avl Bal Rs 9,550.00");

        assertThat(c.messageClass()).isEqualTo(MessageClass.TRANSACTION);
        assertThat(c.reason()).isEqualTo("TRANSACTION:amount-and-completed-action");
    }

    // --- R-012-3 -------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INR 1,234.50 credited to A/c XX1234 by NEFT from ACME PAYROLL",
            "Rs.2,000 spent on SBI Card ending 9012 at AMAZON on 15/11/26. Avl Lmt Rs.48,000",
            "Rs 5,000 withdrawn at ATM from A/c XX5678",
            "You have sent Rs 300 to RAHUL via UPI",
            "Received ₹1,23,456.78 in your A/c X5678 from EMPLOYER",
            "Payment of Rs 15,000 received towards your SBI Card ending 9012",
    })
    void R_012_3_completedActionsAreTransactions(String body) {
        assertThat(classOf(body)).isEqualTo(MessageClass.TRANSACTION);
    }

    @Test
    void R_012_3_actionWordWithoutAmountIsNotTransaction() {
        assertThat(classOf("Your account was debited. Check the app for details.")).isEqualTo(MessageClass.UNKNOWN);
    }

    // --- Safety: real money movements are never ignored ---------------------------------------------

    @Test
    void safety_neverShareOtpFooterDoesNotHideATransaction() {
        assertThat(classOf("INR 450.00 debited from A/c no. XX1234 on 17-11-26 for UPI/P2M/432112345678/SWIGGY. "
                + "Never share your OTP with anyone. Not you? Call 18001035577 - Axis Bank"))
                .isEqualTo(MessageClass.TRANSACTION);
    }

    @Test
    void safety_debitMentioningFailureFallsThroughToTransaction() {
        assertThat(classOf("Rs 250 debited from A/c XX1234. If the txn failed, it will be reversed in 5 days."))
                .isEqualTo(MessageClass.TRANSACTION);
    }

    @Test
    void safety_reversalCreditIsNotFailedTxn() {
        assertThat(classOf("Rs 250 credited to A/c XX1234 as reversal of failed UPI txn 432112345678"))
                .isEqualTo(MessageClass.TRANSACTION);
    }

    @Test
    void safety_cashbackCreditIsNotPromo() {
        assertThat(classOf("Congratulations! Cashback of Rs 50 credited to your A/c XX1234"))
                .isEqualTo(MessageClass.TRANSACTION);
    }

    @Test
    void safety_mandateDebitThatHappenedIsTransaction() {
        assertThat(classOf("Rs 499 debited from A/c XX1234 for mandate NETFLIX. Next debit due on 20-12."))
                .isEqualTo(MessageClass.TRANSACTION);
    }

    // --- Other ignore classes ------------------------------------------------------------------------

    @Test
    void promoWithAmountIsPromo() {
        assertThat(classOf("Get a pre-approved personal loan of up to Rs 5,00,000. Apply now! T&C apply"))
                .isEqualTo(MessageClass.PROMO);
    }

    @Test
    void cardStatementIsStatement() {
        assertThat(classOf("Your SBI Card statement is generated. Total Amt Due Rs 12,345.00, Min Amt Due Rs 620.00, due on 05-12-26."))
                .isEqualTo(MessageClass.STATEMENT);
    }

    @Test
    void otpWithCodeAfterWordIsOtp() {
        assertThat(classOf("OTP for transaction of INR 1,000 at FLIPKART is 482913. Do not share."))
                .isEqualTo(MessageClass.OTP);
    }

    @Test
    void unmatchedIsUnknownWithReason() {
        Classification c = CLASSIFIER.classify("AX-AXISBK-S", "Your KYC is updated.");

        assertThat(c.messageClass()).isEqualTo(MessageClass.UNKNOWN);
        assertThat(c.reason()).isEqualTo("no-rule-matched");
    }

    @Test
    void whitespaceAndCaseDoNotMatter() {
        assertThat(classOf("RS   450.00\n\nDEBITED   from A/c XX1234")).isEqualTo(MessageClass.TRANSACTION);
    }

    @Test
    void nullBodyIsUnknown() {
        assertThat(CLASSIFIER.classify(null, null).messageClass()).isEqualTo(MessageClass.UNKNOWN);
    }

    // --- R-012-4 mapping ------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(MessageClass.class)
    void R_012_4_statusMapping(MessageClass messageClass) {
        Classification c = new Classification(messageClass, "test");
        switch (messageClass) {
            case TRANSACTION, UNKNOWN -> {
                assertThat(c.rawStatus()).isEqualTo(RawStatus.CLASSIFIED);
                assertThat(c.ignoreReason()).isNull();
            }
            case FAILED_TXN -> {
                assertThat(c.rawStatus()).isEqualTo(RawStatus.FAILED);
                assertThat(c.ignoreReason()).isEqualTo("FAILED_TXN");
            }
            default -> {
                assertThat(c.rawStatus()).isEqualTo(RawStatus.IGNORED);
                assertThat(c.ignoreReason()).isEqualTo(messageClass.name());
            }
        }
    }
}
