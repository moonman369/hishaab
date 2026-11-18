package in.moonman.hishaab.match;

import in.moonman.hishaab.match.AccountService.AccountRef;
import in.moonman.hishaab.match.AccountService.AccountView;
import in.moonman.hishaab.match.AccountService.AliasKind;
import in.moonman.hishaab.parse.ParsedMessage.AccountType;
import in.moonman.hishaab.parse.ParsedMessage.Bank;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.support.IntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RecordApplicationEvents
class AccountServiceIT extends IntegrationTest {

    @Autowired
    private AccountService accounts;

    @Autowired
    private ApplicationEvents events;

    @Test
    void AC_030_04_cardAliasResolvesToSavingsAccountWithoutCreatingNew() {
        // Create an Axis savings account with last4 7311
        AccountRef primary = accounts.resolve(Bank.AXIS, "7311", AccountType.SAVINGS).orElseThrow();
        assertThat(primary.nickname()).isEqualTo("Axis ••7311");

        // Add alias 8820 as DEBIT_CARD
        accounts.addAlias(primary.id(), "8820", AliasKind.DEBIT_CARD);

        // Resolving 8820 for Axis must resolve to the 7311 savings account, not create a new one
        Optional<AccountRef> resolved = accounts.resolve(Bank.AXIS, "8820", AccountType.SAVINGS);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(primary.id());
        assertThat(resolved.get().created()).isFalse();
    }

    @Test
    void AC_030_05_mergingAccountsMovesAliasesAndCreatesAliasAndDeletesOld() {
        // Auto-create Axis ••8820 and Axis ••7312
        AccountRef accFrom = accounts.resolve(Bank.AXIS, "8820", AccountType.SAVINGS).orElseThrow();
        AccountRef accInto = accounts.resolve(Bank.AXIS, "7312", AccountType.SAVINGS).orElseThrow();
        assertThat(accFrom.id()).isNotEqualTo(accInto.id());

        // Add a secondary alias to accFrom first to verify alias transfer
        accounts.addAlias(accFrom.id(), "9999", AliasKind.OTHER);

        // Merge accFrom into accInto
        AccountView merged = accounts.merge(accFrom.id(), accInto.id(), AliasKind.DEBIT_CARD);
        assertThat(merged.id()).isEqualTo(accInto.id());

        // Both 8820 and 9999 are now aliases of accInto
        assertThat(merged.aliases()).extracting(AccountService.AliasView::last4)
                .contains("8820", "9999");

        // accFrom is deleted
        assertThat(accounts.list().stream().noneMatch(a -> a.id().equals(accFrom.id()))).isTrue();

        // AccountMerged event was published
        long count = events.stream(AccountMerged.class)
                .filter(e -> e.fromAccountId().equals(accFrom.id()) && e.intoAccountId().equals(accInto.id()))
                .count();
        assertThat(count).isEqualTo(1);

        // Resolving 8820 now resolves to accInto
        AccountRef resolvedFrom = accounts.resolve(Bank.AXIS, "8820", AccountType.SAVINGS).orElseThrow();
        assertThat(resolvedFrom.id()).isEqualTo(accInto.id());
        assertThat(resolvedFrom.created()).isFalse();
    }

    @Test
    void AC_030_06_addingConflictingAliasThrows400() {
        // Create an account that owns 7313
        AccountRef acc1 = accounts.resolve(Bank.AXIS, "7313", AccountType.SAVINGS).orElseThrow();
        // Create another account 7314
        AccountRef acc2 = accounts.resolve(Bank.AXIS, "7314", AccountType.SAVINGS).orElseThrow();

        // Attempting to add 7313 as an alias to acc2 must throw 400 ALIAS_CONFLICT
        assertThatThrownBy(() -> accounts.addAlias(acc2.id(), "7313", AliasKind.DEBIT_CARD))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.code()).isEqualTo("ALIAS_CONFLICT");
                });
    }

    @Test
    void unknownAccountAutoCreatedWithTypeAndNickname() {
        // Unknown credit card for SBI
        AccountRef cc = accounts.resolve(Bank.SBI, "4567", AccountType.CREDIT_CARD).orElseThrow();
        assertThat(cc.created()).isTrue();
        assertThat(cc.nickname()).isEqualTo("SBI card ••4567");
        assertThat(cc.type()).isEqualTo(AccountType.CREDIT_CARD);

        // Unknown savings account for SBI
        AccountRef sav = accounts.resolve(Bank.SBI, "8901", AccountType.SAVINGS).orElseThrow();
        assertThat(sav.created()).isTrue();
        assertThat(sav.nickname()).isEqualTo("SBI ••8901");
        assertThat(sav.type()).isEqualTo(AccountType.SAVINGS);
    }
}
