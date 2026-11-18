package in.moonman.hishaab.parse;

import java.util.Objects;

/**
 * A successful template parse: which template, what kind of record, and the normalised fields.
 *
 * @param bank        null for fund-house (AMC) templates
 * @param accountType null for fund-house templates; used by R-030-1 to create accounts
 * @param confidence  1.0 for templates (R-013-5)
 */
public record ParsedMessage(String templateId, int templateVersion, Bank bank, AccountType accountType,
                            RecordType recordType, ParsedFields fields, double confidence) {

    public ParsedMessage {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(recordType, "recordType");
        Objects.requireNonNull(fields, "fields");
    }

    public enum Bank { AXIS, SBI }

    public enum AccountType { SAVINGS, CREDIT_CARD }

    /** {@code INVESTMENT_CONFIRMATION} records never become transactions (R-031-8). */
    public enum RecordType { TRANSACTION, INVESTMENT_CONFIRMATION }
}
