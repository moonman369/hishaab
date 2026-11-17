package in.moonman.hishaab;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.support.IntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** R-000-3, AC-000-4 and the SPEC §4.4 error format. */
@Import(ErrorHandlingIT.ProbeController.class)
class ErrorHandlingIT extends IntegrationTest {

    @Test
    void AC_000_04_unknownPathWithoutCredentialsIs401Problem() {
        HttpResponse<String> response = get("/api/v1/does-not-exist");

        assertProblem(response, 401, "UNAUTHORIZED");
    }

    @Test
    void AC_000_04_wrongCredentialsAre401() {
        HttpResponse<String> response = send(request("/api/v1/does-not-exist")
                .header("Authorization", basic(TEST_USER, "wrong")).GET().build());

        assertProblem(response, 401, "UNAUTHORIZED");
    }

    @Test
    void R_000_3_unhandledExceptionIs500InternalErrorWithoutStackTrace() {
        HttpResponse<String> response = getAuthenticated("/test-probe/boom");

        assertProblem(response, 500, "INTERNAL_ERROR");
        assertThat(response.body())
                .doesNotContain("secret-internal-detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("\tat ")
                .doesNotContainIgnoringCase("trace");
    }

    @Test
    void apiExceptionKeepsItsStatusAndCode() {
        HttpResponse<String> response = getAuthenticated("/test-probe/conflict");

        assertProblem(response, 409, "PROBE_CONFLICT");
        assertThat(json(response).path("detail").asString()).isEqualTo("probe conflict");
    }

    @Test
    void validationFailureIs400ValidationFailedNamingTheField() {
        HttpResponse<String> response = postJsonAuthenticated("/test-probe/validate", "{}");

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(json(response).path("detail").asString()).isEqualTo("name must not be null");
    }

    @Test
    void unknownPathWithCredentialsIs404Problem() {
        HttpResponse<String> response = getAuthenticated("/api/v1/does-not-exist");

        assertProblem(response, 404, "NOT_FOUND");
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        JsonNode body = json(response);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("type").asString()).startsWith("https://hishaab/errors/");
        assertThat(body.path("title").asString()).isNotBlank();
    }

    record ProbeRequest(@NotNull String name) {
    }

    @TestConfiguration
    @RestController
    static class ProbeController {

        @GetMapping("/test-probe/boom")
        String boom() {
            throw new IllegalStateException("secret-internal-detail");
        }

        @GetMapping("/test-probe/conflict")
        String conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "PROBE_CONFLICT", "probe conflict");
        }

        @PostMapping("/test-probe/validate")
        String validate(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }
    }
}
