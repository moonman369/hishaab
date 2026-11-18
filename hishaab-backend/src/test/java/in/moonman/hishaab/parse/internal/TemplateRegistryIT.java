package in.moonman.hishaab.parse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.moonman.hishaab.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;

/** R-013-1 / AC-013-3: templates are upserted by (id, version); editing a version in place fails the startup check. */
class TemplateRegistryIT extends IntegrationTest {

    @Autowired
    TemplateRegistry registry;

    @Autowired
    TemplateEngine engine;

    private static List<ParseTemplate> yaml(String id, int version, String regex) {
        String text = """
                templates:
                  - id: %s
                    version: %d
                    bank: SBI
                    source: SMS
                    accountType: SAVINGS
                    recordType: TRANSACTION
                    senderPattern: 'SBIINB'
                    regex: '%s'
                    fixed: { direction: DR }
                """.formatted(id, version, regex);
        return TemplateLoader.load(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "it.yml";
            }
        });
    }

    @Test
    void R_013_1_shippedTemplatesAreRegisteredAndActiveAtStartup() {
        Integer active = jdbc.queryForObject("SELECT count(*) FROM parse_template WHERE active", Integer.class);

        assertThat(active).isEqualTo(engine.templates().size());
    }

    @Test
    void AC_013_03_changedRegexWithoutVersionBumpFails() {
        String id = "it.check." + System.nanoTime();
        try {
            registry.sync(yaml(id, 1, "Rs (?<amount>[\\d.]+) debited"));

            assertThatThrownBy(() -> registry.sync(yaml(id, 1, "Rs\\.? (?<amount>[\\d.]+) debited")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without bumping its version");
        } finally {
            registry.sync(engine.templates()); // restore the real active set
        }
    }

    @Test
    void R_013_1_newVersionIsAddedAndOldVersionKeptInactive() {
        String id = "it.version." + System.nanoTime();
        try {
            registry.sync(yaml(id, 1, "Rs (?<amount>[\\d.]+) debited"));
            registry.sync(yaml(id, 2, "Rs\\.? (?<amount>[\\d.]+) debited"));

            assertThat(jdbc.queryForList("SELECT version FROM parse_template WHERE id = ? AND active", Integer.class, id))
                    .containsExactly(2);
            assertThat(jdbc.queryForList("SELECT version FROM parse_template WHERE id = ? ORDER BY version",
                    Integer.class, id)).containsExactly(1, 2);
        } finally {
            registry.sync(engine.templates());
        }
    }
}
