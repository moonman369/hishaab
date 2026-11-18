package in.moonman.hishaab.parse;

import in.moonman.hishaab.shared.Source;
import java.time.Instant;
import java.util.Optional;

/**
 * Public API of the parse module (SPEC-013/014). Pure: no DB access. Extracts and normalises fields from a
 * message classified {@code TRANSACTION} or {@code INVESTMENT_CONFIRMATION}.
 */
public interface ParseService {

    /**
     * @param sender SMS sender header (e.g. {@code AX-AXISBK-S}) or email from-address
     * @return the first template that matches and normalises cleanly (R-013-2), or empty (R-013-4: unparsed path)
     */
    Optional<ParsedMessage> parse(Source source, String sender, String body, Instant receivedAt);
}
