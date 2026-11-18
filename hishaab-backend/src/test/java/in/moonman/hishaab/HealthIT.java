package in.moonman.hishaab;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** AC-000-1: on an empty DB Flyway applies all migrations and health is UP without credentials. */
class HealthIT extends IntegrationTest {

    @Test
    void AC_000_01_healthIsUpWithoutCredentials() {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asString()).isEqualTo("UP");
    }

    @Test
    void AC_000_01_flywayAppliedAllMigrationsSuccessfully() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        Boolean rawMessageExists = jdbc.queryForObject(
                "SELECT to_regclass('public.raw_message') IS NOT NULL", Boolean.class);

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(1);
        assertThat(rawMessageExists).isTrue();
    }
}
