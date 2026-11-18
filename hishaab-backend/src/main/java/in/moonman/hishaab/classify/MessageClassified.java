package in.moonman.hishaab.classify;

/** Published when a raw message is classified {@code TRANSACTION} (R-012-4). Published by SPEC-015. */
public record MessageClassified(String rawMessageId) {
}
