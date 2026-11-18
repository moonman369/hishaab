package in.moonman.hishaab.match;

import in.moonman.hishaab.match.AccountService.AccountRef;
import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class AccountControllerIT extends IntegrationTest {

    @Autowired
    private AccountService accounts;

    @Test
    void endpointsRequireUserAuthentication() {
        HttpResponse<String> res = get("/api/v1/accounts");
        assertThat(res.statusCode()).isEqualTo(401);
    }

    @Test
    void listRenameAddAliasDeleteAliasAndMergeAccounts() {
        // 1. Setup accounts
        AccountRef a1 = accounts.resolve(Bank.AXIS, "5501", AccountType.SAVINGS).orElseThrow();
        AccountRef a2 = accounts.resolve(Bank.AXIS, "5502", AccountType.SAVINGS).orElseThrow();

        // 2. GET /api/v1/accounts
        HttpResponse<String> listRes = getAuthenticated("/api/v1/accounts");
        assertThat(listRes.statusCode()).isEqualTo(200);
        JsonNode listJson = json(listRes);
        assertThat(listJson.isArray()).isTrue();
        assertThat(listJson.size()).isGreaterThanOrEqualTo(2);

        // 3. PATCH /api/v1/accounts/{id} (rename)
        HttpResponse<String> patchRes = send(request("/api/v1/accounts/" + a1.id())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString("{\"nickname\": \"My Salary Account\"}"))
                .build());
        assertThat(patchRes.statusCode()).isEqualTo(200);
        JsonNode patchJson = json(patchRes);
        assertThat(patchJson.get("nickname").asText()).isEqualTo("My Salary Account");

        // 4. POST /api/v1/accounts/{id}/aliases
        HttpResponse<String> addAliasRes = postJsonAuthenticated("/api/v1/accounts/" + a1.id() + "/aliases",
                "{\"last4\": \"5599\", \"kind\": \"DEBIT_CARD\"}");
        assertThat(addAliasRes.statusCode()).isEqualTo(200);
        JsonNode aliasJson = json(addAliasRes);
        assertThat(aliasJson.get("aliases").size()).isEqualTo(1);
        String aliasId = aliasJson.get("aliases").get(0).get("id").asText();

        // 5. DELETE /api/v1/accounts/{id}/aliases/{aliasId}
        HttpResponse<String> delRes = send(request("/api/v1/accounts/" + a1.id() + "/aliases/" + aliasId)
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .DELETE()
                .build());
        assertThat(delRes.statusCode()).isEqualTo(204);

        // 6. POST /api/v1/accounts/{id}/merge-into/{targetId}
        HttpResponse<String> mergeRes = postJsonAuthenticated("/api/v1/accounts/" + a2.id() + "/merge-into/" + a1.id(),
                "{\"kind\": \"DEBIT_CARD\"}");
        assertThat(mergeRes.statusCode()).isEqualTo(200);
        JsonNode mergeJson = json(mergeRes);
        assertThat(mergeJson.get("aliases").size()).isEqualTo(1);
        assertThat(mergeJson.get("aliases").get(0).get("last4").asText()).isEqualTo("5502");
    }

    @Test
    void addAliasConflictReturns400ProblemDetail() {
        AccountRef a1 = accounts.resolve(Bank.SBI, "6601", AccountType.SAVINGS).orElseThrow();
        AccountRef a2 = accounts.resolve(Bank.SBI, "6602", AccountType.SAVINGS).orElseThrow();

        // Attempt to add 6601 as alias of a2
        HttpResponse<String> res = postJsonAuthenticated("/api/v1/accounts/" + a2.id() + "/aliases",
                "{\"last4\": \"6601\", \"kind\": \"DEBIT_CARD\"}");
        assertThat(res.statusCode()).isEqualTo(400);
        JsonNode err = json(res);
        assertThat(err.get("code").asText()).isEqualTo("ALIAS_CONFLICT");
    }
}
