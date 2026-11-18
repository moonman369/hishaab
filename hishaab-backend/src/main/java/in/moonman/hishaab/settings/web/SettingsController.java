package in.moonman.hishaab.settings.web;

import in.moonman.hishaab.settings.SettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** SPEC-001: {@code GET/PATCH /api/v1/settings}. Body and response are a flat object keyed by setting name. */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    private final SettingsService settings;

    SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    Map<String, JsonNode> get() {
        return settings.all();
    }

    @PatchMapping
    Map<String, JsonNode> patch(@RequestBody Map<String, JsonNode> changes) {
        return settings.update(changes);
    }
}
