package in.moonman.hishaab.parse.internal;

/** A template capture that cannot be turned into a clean field. The template then does not count as a match. */
class NormalisationException extends RuntimeException {

    NormalisationException(String message) {
        super(message);
    }
}
