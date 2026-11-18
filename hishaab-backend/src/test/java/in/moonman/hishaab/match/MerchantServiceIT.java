package in.moonman.hishaab.match;

import in.moonman.hishaab.match.MerchantService.MerchantRef;
import in.moonman.hishaab.match.MerchantService.MerchantType;
import in.moonman.hishaab.match.MerchantService.MerchantView;
import in.moonman.hishaab.support.IntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;

@RecordApplicationEvents
class MerchantServiceIT extends IntegrationTest {

    @Autowired
    private MerchantService merchants;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void cleanMerchants() {
        jdbc.update("DELETE FROM transaction_evidence");
        jdbc.update("DELETE FROM transaction");
        jdbc.update("DELETE FROM merchant_alias");
        jdbc.update("DELETE FROM merchant");
    }

    @Test
    void AC_030_01_swiggyVariantsResolveToSameMerchant() {
        Optional<MerchantRef> ref1 = merchants.resolve("SWIGGY*BLR");
        assertThat(ref1).isPresent();
        assertThat(ref1.get().created()).isTrue();

        Optional<MerchantRef> ref2 = merchants.resolve("Swiggy Ltd");
        assertThat(ref2).isPresent();
        assertThat(ref2.get().created()).isFalse();
        assertThat(ref2.get().id()).isEqualTo(ref1.get().id());
    }

    @Test
    void AC_030_02_personalUpiResolvesToPerson() {
        Optional<MerchantRef> ref = merchants.resolve("rahul.k@okaxis");
        assertThat(ref).isPresent();
        assertThat(ref.get().type()).isEqualTo(MerchantType.PERSON);
    }

    @Test
    void AC_030_03_mergeMovesAliasesAndDeletesOldMerchant() {
        // Create merchant A (e.g. from raw "Zomato Online")
        MerchantRef m1 = merchants.resolve("Zomato Online").orElseThrow();
        // Create merchant B (e.g. from raw "Zomato Media")
        MerchantRef m2 = merchants.resolve("Zomato Media").orElseThrow();
        assertThat(m1.id()).isNotEqualTo(m2.id());

        // Merge m1 into m2
        MerchantView merged = merchants.merge(m1.id(), m2.id());

        assertThat(merged.id()).isEqualTo(m2.id());
        assertThat(merged.aliases()).contains("ZOMATO ONLINE", "ZOMATO MEDIA");

        // Old merchant m1 is deleted
        List<MerchantView> found = merchants.search(m1.name(), 10);
        assertThat(found.stream().noneMatch(v -> v.id().equals(m1.id()))).isTrue();

        // MerchantMerged event was published
        long count = events.stream(MerchantMerged.class)
                .filter(e -> e.fromMerchantId().equals(m1.id()) && e.intoMerchantId().equals(m2.id()))
                .count();
        assertThat(count).isEqualTo(1);

        // Resolving m1's original string now resolves to m2
        Optional<MerchantRef> resolvedAgain = merchants.resolve("Zomato Online");
        assertThat(resolvedAgain).isPresent();
        assertThat(resolvedAgain.get().id()).isEqualTo(m2.id());
        assertThat(resolvedAgain.get().created()).isFalse();
    }
}
