package in.moonman.hishaab.classify;

/**
 * Public API of the classify module (SPEC-012). A pure function of its inputs: no DB access, no side effects
 * (R-012-5).
 */
public interface ClassifyService {

    Classification classify(String sender, String body);
}
