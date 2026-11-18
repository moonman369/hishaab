package in.moonman.hishaab.ingest;

/**
 * Published once per newly stored raw message (R-011-5). Listeners must run after commit
 * ({@code @ApplicationModuleListener} / {@code @TransactionalEventListener}), so the row is visible to them.
 */
public record RawMessageReceived(String rawMessageId) {
}
