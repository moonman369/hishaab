package in.moonman.hishaab.parse.internal;

import in.moonman.hishaab.parse.ParseService;
import java.util.List;

/** Test-only access to the real template engine without a Spring context. */
public final class TestParsers {

    private static final TemplateEngine ENGINE = new TemplateEngine();

    private TestParsers() {
    }

    public static ParseService fromClasspath() {
        return ENGINE;
    }

    public static List<String> templateIds() {
        return ENGINE.templates().stream().map(ParseTemplate::id).toList();
    }
}
