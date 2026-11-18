package in.moonman.hishaab.parse;

import java.util.UUID;

/** Storage of parsed records (the parse module owns {@code parsed_record}). One record per raw message. */
public interface ParsedRecords {

    /** Stores the parse of one raw message; returns the new record's id. */
    UUID save(String rawMessageId, ParsedMessage parsed);

    /** Derived-data cleanup for reprocessing (R-015-7). */
    int deleteForRawMessage(String rawMessageId);

    int deleteAll();
}
