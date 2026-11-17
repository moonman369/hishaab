package in.moonman.hishaab;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/** R-000-1 / AC-000-2 / H-4: modules talk only through their public API package. */
class ModularityTest {

    @Test
    void AC_000_02_applicationModulesVerify() {
        ApplicationModules modules = ApplicationModules.of(HishaabBackendApplication.class);
        assertThatCode(modules::verify).doesNotThrowAnyException();
    }

    /**
     * Proves the check has teeth without planting a violation in production code: the test-only fixture
     * {@code fixture.modulith.violating} has module {@code alpha} calling {@code beta.internal}.
     */
    @Test
    void AC_000_02_callIntoAnotherModulesInternalPackageFailsVerification() {
        ImportOption includeTestClasses = location -> true;
        ApplicationModules fixture = ApplicationModules.of("fixture.modulith.violating", includeTestClasses);

        assertThatThrownBy(fixture::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("beta.internal.BetaInternals");
    }
}
