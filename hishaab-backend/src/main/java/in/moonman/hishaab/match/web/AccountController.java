package in.moonman.hishaab.match.web;

import in.moonman.hishaab.match.AccountService;
import in.moonman.hishaab.match.AccountService.AccountView;
import in.moonman.hishaab.match.AccountService.AliasKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** R-030-7 (user auth). */
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
    AccountView rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return accounts.rename(id, request.nickname());
    }

    @PostMapping("/{id}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    AccountView addAlias(@PathVariable UUID id, @Valid @RequestBody AliasRequest request) {
        return accounts.addAlias(id, request.last4(), request.kind());
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeAlias(@PathVariable UUID id, @PathVariable UUID aliasId) {
        accounts.removeAlias(id, aliasId);
    }

    @PostMapping("/{id}/merge-into/{targetId}")
    AccountView merge(@PathVariable UUID id, @PathVariable UUID targetId,
                      @RequestBody(required = false) MergeRequest request) {
        return accounts.merge(id, targetId, request == null ? null : request.aliasKind());
    }

    record RenameRequest(@NotBlank @Size(max = 60) String nickname) {
    }

    record AliasRequest(@NotNull String last4, AliasKind kind) {
    }

    /** {@code aliasKind}: what the merged account's old number becomes (default OTHER). */
    record MergeRequest(AliasKind aliasKind) {
    }
}
