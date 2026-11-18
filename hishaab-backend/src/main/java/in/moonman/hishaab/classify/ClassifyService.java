package in.moonman.hishaab.classify;

/**
 * Public API of the classify module (SPEC-012). A pure function of its inputs: no DB access, no side effects
 * (R-012-5).
 */
public interface ClassifyService {

    Classification classify(String sender, String body);

    /**
     * Whether the body contains something that looks like an amount, using the same {@code amount} pattern as the
     * rules. Used for "class {@code UNKNOWN} with an amount pattern → UNPARSED review" (R-015-6).
     */
    boolean mentionsAmount(String body);
}
