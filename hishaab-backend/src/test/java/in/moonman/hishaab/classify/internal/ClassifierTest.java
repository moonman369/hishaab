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

    // --- AC-012-7 / AC-012-8 (fund houses) -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Your SIP Purchase of Rs.999.95 in Folio 55500011 in Gamma Fund Growth for 29.1 units has been processed for NAV of Rs.34.33 on 05-Oct-2026",
            "Folio XXXXXXX1122 - Your SIP instalment of Rs. 999.95 towards Alpha Fund is processed. Units Allotted: 21.5.",
            "Your Purchase of Rs.999.95 for folio 7000123/38 in Beta Fund-Reg-Growth is processed at NAV 50.357",
    })
    void AC_012_07_fundHouseAllotmentIsInvestmentConfirmation(String body) {
        assertThat(classOf(body)).isEqualTo(MessageClass.INVESTMENT_CONFIRMATION);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Folio XXXXXXX1122 - Your SIP instalment of Rs. 1000.00 towards Alpha Fund will be debited on 05/10/2026.",
            "Dear Investor, SIP of Rs.1000 dated 05-10-2026 under Folio 55500011 in Gamma Fund is due for debit from your bank account",
            "Alert: SIP instalment of Rs.1000 in Beta Fund in Folio 7000123 is due on 05-11-2026.",
            "Your Systematic Investment Plan (SIP) submitted under folio no.12121212 has been registered with the following details",
            "Your UPI-Mandate for Rs.699.00 is successfully created towards StreamCo from A/c No: XXXXXX4092.",
    })
    void AC_012_08_fundHouseRemindersAndRegistrationsAreMandateReminders(String body) {
        assertThat(classOf(body)).isEqualTo(MessageClass.MANDATE_REMINDER);
    }

    @Test
    void safety_couldNotBeProcessedIsStillFailed() {
        assertThat(classOf("Your payment of Rs 500 could not be processed. Please retry."))
                .isEqualTo(MessageClass.FAILED_TXN);
    }

    @Test
    void safety_alreadyBeingDebitedIsNotACompletedDebit() {
        assertThat(classOf("Your SIP of Rs.1000 is due on 05-NOV-2026. Ignore if your account is already being debited."))
                .isEqualTo(MessageClass.MANDATE_REMINDER);
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
            case TRANSACTION, INVESTMENT_CONFIRMATION, UNKNOWN -> {
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
