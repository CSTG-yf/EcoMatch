package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic-question tests for the organization-binding cross-check guard. Every question string
 * below is composed from public {@link BankSemanticRegistry} catalog identities (ORG### names and
 * aliases are generated uniformly for cities A..M); no evaluation question text, sample
 * identifier or gold artifact appears here.
 *
 * <p>
 * Contract under test: the guard fires a repairable {@code ORG_BINDING_CONFLICT} message only
 * when the question literally names exactly one catalog organization and the plan binds a
 * different one; ambiguous (0 or 2+ named organizations), province-wide or correct plans all
 * fall open.
 */
class BankOrgBindingGuardTest {

    // Catalog identities (see BankFinancialLexicon buildOrganizations: 13 orgs, cities A..M):
    // ORG004 = 江苏省D市农商行 (aliases: D行 / D市农商行 / D农商行), ORG003 = 江苏省C市农商行,
    // ORG007 = 江苏省G市农商行. The sanity check below locks this assumption.
    private static final String ORG_D = "ORG004";
    private static final String ORG_C = "ORG003";
    private static final String ORG_G = "ORG007";
    private static final String NAME_D = "江苏省D市农商行";
    private static final String ALIAS_D = "D农商行";

    @Test
    void catalogSanityNamesAndAliasesExistAsAssumed() {
        assertEquals(NAME_D, BankSemanticRegistry.organizations().get(ORG_D).name());
        assertTrue(BankSemanticRegistry.organizations().get(ORG_D).aliases().contains(ALIAS_D));
        assertTrue(BankSemanticRegistry.organizations().containsKey(ORG_C));
        assertTrue(BankSemanticRegistry.organizations().containsKey(ORG_G));
    }

    @Test
    void firesWhenTheQuestionUniquelyNamesOneOrganizationButThePlanBindsAnother() {
        Optional<String> conflict = BankOrgBindingGuard.conflict(
                NAME_D + "的存款余额和贷款余额分别是多少", List.of(ORG_G));

        assertTrue(conflict.isPresent());
        String message = conflict.get();
        assertTrue(message.startsWith(BankOrgBindingGuard.ERROR_CODE),
                "message must open with the machine code: " + message);
        assertTrue(message.contains(ORG_D), "expected code missing: " + message);
        assertTrue(message.contains(NAME_D), "expected name missing: " + message);
        assertTrue(message.contains(ORG_G), "wrongly bound code missing: " + message);
        assertTrue(message.contains("请改为 [" + ORG_D + "]"),
                "repair instruction missing: " + message);
    }

    @Test
    void firesOnAnAliasMentionThatIsNotTheFullName() {
        Optional<String> conflict =
                BankOrgBindingGuard.conflict("想知道" + ALIAS_D + "今年的净利润", List.of(ORG_G));

        assertTrue(conflict.isPresent());
        assertTrue(conflict.get().startsWith(BankOrgBindingGuard.ERROR_CODE));
        assertTrue(conflict.get().contains(ORG_D));
        assertTrue(conflict.get().contains(ORG_G));
    }

    @Test
    void staysSilentWhenThePlanBindsTheUniquelyNamedOrganization() {
        String question = NAME_D + "的存款余额是多少";

        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(question, List.of(ORG_D)));
        // A superset binding that still contains the uniquely named organization falls open;
        // extra organizations stay the validator's contract problem, not this guard's.
        assertEquals(Optional.empty(),
                BankOrgBindingGuard.conflict(question, List.of(ORG_G, ORG_D)));
    }

    @Test
    void staysSilentWhenTheQuestionNamesTwoOrganizations() {
        // Multi-organization comparison: two catalog organizations are named, so no single
        // wrong binding can be proven and the guard must not fire.
        String comparison = "比较" + NAME_D + "与江苏省C市农商行的存款余额";

        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(comparison, List.of(ORG_G)));
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(comparison, List.of()));
    }

    @Test
    void staysSilentWhenNoOrganizationIsNamed() {
        // Generic province wording: "农商行" alone matches no catalog name or alias.
        String provinceQuestion = "全省各家农商行的存款余额和贷款余额是多少";

        assertEquals(Optional.empty(),
                BankOrgBindingGuard.conflict(provinceQuestion, List.of(ORG_G)));
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(provinceQuestion, List.of()));
    }

    @Test
    void staysSilentForAProvinceWidePlanEvenWhenOneOrganizationIsNamed() {
        String question = NAME_D + "的网点数量是多少";

        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(question, List.of()));
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(question, null));
    }

    @Test
    void staysSilentOnBlankOrNullInputs() {
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(null, List.of(ORG_G)));
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict("", List.of(ORG_G)));
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict("   ", List.of(ORG_G)));
        // A plan list that only carries blank entries cannot prove a wrong binding either.
        assertEquals(Optional.empty(), BankOrgBindingGuard.conflict(NAME_D, Arrays.asList(" ", null)));
    }

    @Test
    void firedMessageContainsTheMachineCodePrefixOnlyOnceAtTheFront() {
        Optional<String> conflict = BankOrgBindingGuard.conflict(
                NAME_D + "的存款余额是多少", List.of(ORG_C, ORG_G));

        assertTrue(conflict.isPresent());
        String message = conflict.get();
        assertTrue(message.indexOf(BankOrgBindingGuard.ERROR_CODE) == 0,
                "machine code must be the very first token: " + message);
        assertFalse(message.substring(1).contains(BankOrgBindingGuard.ERROR_CODE),
                "machine code must not repeat inside the message: " + message);
        // Multiple bound codes are listed inside one bracket pair, comma separated.
        assertTrue(message.contains("[" + ORG_C + ", " + ORG_G + "]"),
                "bound code list formatting unexpected: " + message);
    }
}
