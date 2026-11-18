package in.moonman.hishaab.auth.internal;

/** Role names (without the {@code ROLE_} prefix). */
final class Roles {

    /** The human owner (temporary basic auth until SPEC-050). */
    static final String USER = "USER";

    /** A registered phone using an {@code hdt_} token (R-010-2). */
    static final String DEVICE = "DEVICE";

    private Roles() {
    }
}
