package in.moonman.hishaab.parse;

import in.moonman.hishaab.parse.ParsedMessage.RecordType;
import java.util.UUID;

/**
 * Published after a parsed record is stored (R-015-5, R-015-8), inside the processing transaction; listeners run
 * after commit. {@code INVESTMENT_CONFIRMATION} records are for SPEC-046, never the matcher (R-031-8).
 */
public record RecordParsed(UUID parsedRecordId, String rawMessageId, RecordType recordType) {
}
