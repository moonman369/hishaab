package in.moonman.hishaab.shared;

import org.springframework.http.HttpStatus;

/**
 * An error the API reports to clients as an RFC 9457 ProblemDetail (SPEC §4.4).
 * {@code code} is a stable machine string; the message is the human-readable {@code detail}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
