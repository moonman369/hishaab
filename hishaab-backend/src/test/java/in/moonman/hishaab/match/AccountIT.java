package in.moonman.hishaab.match;

import static org.assertj.core.api.Assertions.assertThat;

import in.moonman.hishaab.ingest.ProcessingService;
import in.moonman.hishaab.match.AccountService.AccountRef;
import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.parse.internal.TestParsers;
import in.moonman.hishaab.shared.Source;
import in.moonman.hishaab.support.IntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** SPEC-030 accounts. The DB is shared, so each test uses fresh account numbers. */
@Import(AccountIT.Recorder.class)
class AccountIT extends IntegrationTest {

    private static final AtomicInteger NEXT = new AtomicInteger(1000 + ThreadLocalRandom.current().nextInt(6000));

    @Autowired
    AccountService accounts;

    @Autowired
    ProcessingService processing;

    @Autowired
    Recorder recorder;

    @BeforeEach
    void clear() {
        recorder.merged.clear();
    }

    private static String nextLast4() {
        return String.format("%04d", NEXT.getAndIncrement() % 10_000);
    }

    @Test
    void R_030_1_unknownNumberAutoCreatesOnceWithDefaultNickname() {
        String savings = nextLast4();
        String card = nextLast4();

        AccountRef first = accounts.resolve(Bank.AXIS, savings, AccountType.SAVINGS).orElseThrow();
        AccountRef again = accounts.resolve(Bank.AXIS, savings, AccountType.SAVINGS).orElseThrow();
        AccountRef cc = accounts.resolve(Bank.AXIS, card, AccountType.CREDIT_CARD).orElseThrow();

        assertThat(first.created()).isTrue();
        assertThat(first.nickname()).isEqualTo("Axis ••" + savings);
        assertThat(again.created()).isFalse();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(cc.type()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(cc.nickname()).isEqualTo("Axis card ••" + card);
    }

    @Test
    void R_030_1_missingBankOrNumberResolvesToNothing() {
        assertThat(accounts.resolve(null, "1234", AccountType.SAVINGS)).isEmpty();
        assertThat(accounts.resolve(Bank.SBI, null, AccountType.SAVINGS)).isEmpty();
        assertThat(accounts.resolve(Bank.SBI, "12", AccountType.SAVINGS)).isEmpty();
    }

    @Test
    void AC_030_04_autopayEmailForAliasedCardResolvesToTheSavingsAccount() {
        String savings = nextLast4();
        String card = nextLast4();
        AccountRef account = accounts.resolve(Bank.AXIS, savings, AccountType.SAVINGS).orElseThrow();
        HttpResponse<String> added = postJsonAuthenticated("/api/v1/accounts/" + account.id() + "/aliases",
                "{\"last4\":\"" + card + "\",\"kind\":\"DEBIT_CARD\"}");
        assertThat(added.statusCode()).isEqualTo(201);

        ParsedMessage parsed = TestParsers.fromClasspath().parse(Source.EMAIL, "alerts@axis.bank.in",
                "Auto Pay of INR 199.00 for MUSICBOX FAMILY processed\n\nAuto Pay for e-mandate of INR 199.00 for "
                        + "MUSICBOX FAMILY has been processed on your Axis Bank Card no. XX" + card + " on 15-10-2026 at "
                        + "06:30:00 IST. The transaction ID is 263000999888777.", Instant.now()).orElseThrow();
        int before = accountCount();

        AccountRef resolved = accounts.resolve(parsed.bank(), parsed.fields().accountLast4(), parsed.accountType())
                .orElseThrow();

        assertThat(resolved.id()).isEqualTo(account.id());
        assertThat(resolved.created()).isFalse();
        assertThat(accountCount()).isEqualTo(before);
    }

    @Test
    void AC_030_05_mergingAutoCreatedCardAccountMovesItAndSurvivesRebuild() {
        String savings = nextLast4();
        String card = nextLast4();
        AccountRef into = accounts.resolve(Bank.AXIS, savings, AccountType.SAVINGS).orElseThrow();
        AccountRef from = accounts.resolve(Bank.AXIS, card, AccountType.SAVINGS).orElseThrow();

        HttpResponse<String> merged = send(request("/api/v1/accounts/" + from.id() + "/merge-into/" + into.id())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"aliasKind\":\"DEBIT_CARD\"}")).build());

        assertThat(merged.statusCode()).isEqualTo(200);
        assertThat(json(merged).path("aliases").values()).extracting(a -> a.path("last4").asString()).contains(card);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Integer.class, from.id())).isZero();
        assertThat(recorder.merged).containsExactly(new AccountMerged(from.id(), into.id()));

        processing.reprocessAll(); // accounts and aliases are not derived data

        assertThat(accounts.resolve(Bank.AXIS, card, AccountType.SAVINGS).orElseThrow().id()).isEqualTo(into.id());
    }

    @Test
    void AC_030_06_aliasThatIsAnotherAccountsNumberIsAConflict() {
        AccountRef a = accounts.resolve(Bank.SBI, nextLast4(), AccountType.SAVINGS).orElseThrow();
        String taken = nextLast4();
        accounts.resolve(Bank.SBI, taken, AccountType.SAVINGS).orElseThrow();

        HttpResponse<String> response = postJsonAuthenticated("/api/v1/accounts/" + a.id() + "/aliases",
                "{\"last4\":\"" + taken + "\",\"kind\":\"DEBIT_CARD\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("ALIAS_CONFLICT");
    }

    @Test
    void AC_030_06_aliasAlreadyUsedByAnotherAccountIsAConflict() {
        AccountRef a = accounts.resolve(Bank.SBI, nextLast4(), AccountType.SAVINGS).orElseThrow();
        AccountRef b = accounts.resolve(Bank.SBI, nextLast4(), AccountType.SAVINGS).orElseThrow();
        String card = nextLast4();
        accounts.addAlias(a.id(), card, AccountService.AliasKind.DEBIT_CARD);

        HttpResponse<String> response = postJsonAuthenticated("/api/v1/accounts/" + b.id() + "/aliases",
                "{\"last4\":\"" + card + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("ALIAS_CONFLICT");
    }

    @Test
    void aliasOfADifferentBankDoesNotConflict() {
        String number = nextLast4();
        AccountRef sbi = accounts.resolve(Bank.SBI, nextLast4(), AccountType.SAVINGS).orElseThrow();
        accounts.resolve(Bank.AXIS, number, AccountType.SAVINGS).orElseThrow();

        assertThat(accounts.addAlias(sbi.id(), number, AccountService.AliasKind.DEBIT_CARD).aliases())
                .extracting(AccountService.AliasView::last4).contains(number);
    }

    @Test
    void mergeRulesAreEnforced() {
        AccountRef axis = accounts.resolve(Bank.AXIS, nextLast4(), AccountType.SAVINGS).orElseThrow();
        AccountRef sbi = accounts.resolve(Bank.SBI, nextLast4(), AccountType.SAVINGS).orElseThrow();
        AccountRef card = accounts.resolve(Bank.AXIS, nextLast4(), AccountType.CREDIT_CARD).orElseThrow();

        assertThat(code(mergeCall(axis.id(), axis.id()))).isEqualTo("MERGE_SELF");
        assertThat(code(mergeCall(sbi.id(), axis.id()))).isEqualTo("ACCOUNT_MERGE_MISMATCH");
        assertThat(code(mergeCall(card.id(), axis.id()))).isEqualTo("ACCOUNT_MERGE_MISMATCH");
        HttpResponse<String> unknown = mergeCall(UUID.randomUUID(), axis.id());
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    void renameListAndRemoveAlias() {
        AccountRef a = accounts.resolve(Bank.AXIS, nextLast4(), AccountType.SAVINGS).orElseThrow();
        String card = nextLast4();
        UUID aliasId = accounts.addAlias(a.id(), card, AccountService.AliasKind.DEBIT_CARD).aliases().getFirst().id();

        HttpResponse<String> renamed = send(request("/api/v1/accounts/" + a.id())
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"Salary account\"}")).build());
        assertThat(json(renamed).path("nickname").asString()).isEqualTo("Salary account");

        JsonNode listed = json(getAuthenticated("/api/v1/accounts"));
        assertThat(listed.values()).anySatisfy(acc -> assertThat(acc.path("id").asString()).isEqualTo(a.id().toString()));

        HttpResponse<String> removed = send(request("/api/v1/accounts/" + a.id() + "/aliases/" + aliasId)
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD)).DELETE().build());
        assertThat(removed.statusCode()).isEqualTo(204);
        assertThat(accounts.resolve(Bank.AXIS, card, AccountType.SAVINGS).orElseThrow().id()).isNotEqualTo(a.id());
    }

    @Test
    void accountsNeedUserAuth() {
        assertThat(get("/api/v1/accounts").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> mergeCall(UUID from, UUID into) {
        return send(request("/api/v1/accounts/" + from + "/merge-into/" + into)
                .header("Authorization", basic(TEST_USER, TEST_PASSWORD))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private static String code(HttpResponse<String> response) {
        return json(response).path("code").asString();
    }

    private int accountCount() {
        return jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
    }

    @TestConfiguration
    static class Recorder {
        final List<AccountMerged> merged = new CopyOnWriteArrayList<>();

        @EventListener
        void on(AccountMerged e) {
            merged.add(e);
        }
    }
}
