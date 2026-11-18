package in.moonman.hishaab.match.web;

import in.moonman.hishaab.match.AccountService;
import in.moonman.hishaab.match.AccountService.AccountView;
import in.moonman.hishaab.match.AccountService.AliasKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for accounts and card aliases (SPEC-030, R-030-7).
 * User-authenticated.
 */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<AccountView> list() {
        return accounts.list();
    }

    @PatchMapping("/{id}")
    AccountView rename(@PathVariable UUID id, @Valid @RequestBody RenameAccountRequest request) {
        return accounts.rename(id, request.nickname());
    }

    @PostMapping("/{id}/aliases")
    AccountView addAlias(@PathVariable UUID id, @Valid @RequestBody AddAliasRequest request) {
        return accounts.addAlias(id, request.last4(), request.kind());
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeAlias(@PathVariable UUID id, @PathVariable UUID aliasId) {
        accounts.removeAlias(id, aliasId);
    }

    @PostMapping("/{id}/merge-into/{targetId}")
    AccountView merge(@PathVariable UUID id, @PathVariable UUID targetId,
                      @RequestBody(required = false) MergeAccountRequest request) {
        AliasKind kind = request != null && request.kind() != null ? request.kind() : AliasKind.DEBIT_CARD;
        return accounts.merge(id, targetId, kind);
    }

    record RenameAccountRequest(@NotBlank String nickname) {
    }

    record AddAliasRequest(
            @NotNull @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits") String last4,
            @NotNull AliasKind kind) {
    }

    record MergeAccountRequest(AliasKind kind) {
    }
}
