package in.moonman.hishaab.match.web;

import in.moonman.hishaab.match.MerchantService;
import in.moonman.hishaab.match.MerchantService.MerchantType;
import in.moonman.hishaab.match.MerchantService.MerchantView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-030 merchant endpoints (HLD §4.11): search, correct name/type, merge. User auth. */
@RestController
@Validated
@RequestMapping("/api/v1/merchants")
class MerchantController {

    private final MerchantService merchants;

    MerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @GetMapping
    List<MerchantView> search(@RequestParam(defaultValue = "") String q,
                              @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return merchants.search(q, limit);
    }

    @PatchMapping("/{id}")
    MerchantView update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return merchants.update(id, request.name(), request.type(), request.alwaysAsk());
    }

    @PostMapping("/{id}/merge-into/{targetId}")
    MerchantView merge(@PathVariable UUID id, @PathVariable UUID targetId) {
        return merchants.merge(id, targetId);
    }

    record UpdateRequest(@Size(min = 1, max = 100) String name, MerchantType type, Boolean alwaysAsk) {
    }
}
