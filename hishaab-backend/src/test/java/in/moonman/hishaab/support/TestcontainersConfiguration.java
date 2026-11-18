package in.moonman.hishaab.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres for integration tests; Boot wires the datasource to it via {@link ServiceConnection}.
 * One container is shared by every Spring test context in the JVM (contexts differ by {@code @Import}s),
 * so tests must not assume an empty database, only a migrated one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
