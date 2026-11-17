package in.moonman.hishaab.shared.internal;

import in.moonman.hishaab.shared.ApiException;
import java.net.URI;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 ProblemDetail with a stable {@code code} (SPEC §4.4, R-000-3).
 * Stack traces are logged server-side only, never returned.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = "https://hishaab/errors/";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail body = problem(ex.status(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("event=unhandled_exception type={}", ex.getClass().getName(), ex);
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred");
        return ResponseEntity.internalServerError().body(body);
    }

    /** Validation failures name the offending fields, e.g. {@code messages[3].receivedAtMs must not be null}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "Invalid request content";
        }
        ProblemDetail body = problem(status, "VALIDATION_FAILED", detail);
        body.setTitle("Invalid request");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Framework-raised errors (validation, 404, 405, bad JSON ...) get a {@code code} too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.getBody(); // e.g. NoResourceFoundException: body is built lazily otherwise
        }
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            String code = codeFor(ex, statusCode);
            pd.setProperty("code", code);
            pd.setType(URI.create(TYPE_BASE + slug(code)));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
            return "VALIDATION_FAILED";
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.name() : "HTTP_" + status.value();
    }

    static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + slug(code)));
        pd.setProperty("code", code);
        return pd;
    }

    private static String slug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
