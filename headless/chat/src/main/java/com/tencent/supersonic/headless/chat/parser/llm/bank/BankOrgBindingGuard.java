package com.tencent.supersonic.headless.chat.parser.llm.bank;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-checks the organization bindings of a plan against the organizations literally named in
 * the question text.
 *
 * <p>
 * {@link BankQueryPlanValidator} only proves that plan organization codes are registry members;
 * it cannot see the failure mode where the model binds a <em>legal but wrong</em> organization.
 * Such a plan stays valid, returns non-empty rows and is invisible to every downstream repair
 * loop. This guard turns that silent error into a repairable one: when the question text names
 * exactly one catalog organization (by name or any alias) and the plan binds a different one,
 * the guard produces a repairable error message carrying the {@link #ERROR_CODE} machine prefix.
 *
 * <p>
 * The check is deliberately conservative (fail-open). It fires only when all of the following
 * hold, so ambiguous or province-wide questions are never blocked:
 *
 * <ul>
 * <li>the question text contains the name or an alias of exactly one registered organization —
 * zero hits (province scope, generic wording) or two or more hits (multi-organization
 * comparison) stay silent;</li>
 * <li>the plan binds at least one organization (an empty list means province-wide scope and
 * stays silent);</li>
 * <li>the plan does not already contain the uniquely named organization code.</li>
 * </ul>
 *
 * <p>
 * Evidence comes from the shared {@link BankSemanticRegistry} catalog only — no evaluation
 * question text, sample identifiers or gold artifacts are involved. Matching mirrors the
 * deterministic contains-based organization scan of the intent recognizer, keeping the two
 * views of "which organization does this question mention" consistent.
 */
public final class BankOrgBindingGuard {

    /**
     * Machine-readable error-code prefix; must open every fired message. Lowercase snake_case so
     * {@code BankPlanGenStrategy#repairErrorCode} extracts it into the repair diagnostics — its
     * extraction regex accepts lowercase-leading codes only.
     */
    public static final String ERROR_CODE = "org_binding_conflict";

    private BankOrgBindingGuard() {}

    /**
     * Returns a repairable error message when the question uniquely names one catalog
     * organization but the plan binds a different one; empty when the guard cannot prove a
     * conflict (no or multiple named organizations, province-wide plan, correct binding, or
     * blank input).
     *
     * @param queryText the natural-language question text
     * @param planOrganizationCodes the organization codes bound by the plan, in plan order
     * @return the {@link #ERROR_CODE}-prefixed repair message, or {@link Optional#empty()}
     */
    public static Optional<String> conflict(String queryText, List<String> planOrganizationCodes) {
        if (queryText == null || queryText.isBlank()) {
            return Optional.empty();
        }
        Set<String> mentioned = uniquelyMentionedOrganizations(queryText);
        if (mentioned.size() != 1) {
            // Zero hits = province scope or generic wording; several hits = multi-organization
            // comparison. Neither can prove a wrong single binding, so both fall open.
            return Optional.empty();
        }
        String expectedCode = mentioned.iterator().next();
        Set<String> boundCodes = normalizedPlanCodes(planOrganizationCodes);
        if (boundCodes.isEmpty()) {
            // Empty plan organizations compile to the province-wide scope, not to a wrong org.
            return Optional.empty();
        }
        boolean bindsExpected = boundCodes.stream()
                .anyMatch(code -> code.equalsIgnoreCase(expectedCode));
        if (bindsExpected) {
            return Optional.empty();
        }
        return Optional.of(firedMessage(expectedCode, boundCodes));
    }

    /** Catalog organizations whose name or any alias appears literally in the question text. */
    private static Set<String> uniquelyMentionedOrganizations(String queryText) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (BankSemanticRegistry.OrganizationDefinition organization : BankSemanticRegistry
                .organizations().values()) {
            if (mentions(queryText, organization)) {
                codes.add(organization.code());
            }
        }
        return codes;
    }

    /** Same deterministic contains-style evidence as the recognizer's organization scan. */
    private static boolean mentions(String queryText,
            BankSemanticRegistry.OrganizationDefinition organization) {
        if (queryText.contains(organization.name())) {
            return true;
        }
        for (String alias : organization.aliases()) {
            if (alias != null && !alias.isBlank() && queryText.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizedPlanCodes(List<String> planOrganizationCodes) {
        if (planOrganizationCodes == null || planOrganizationCodes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : planOrganizationCodes) {
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        }
        return codes;
    }

    private static String firedMessage(String expectedCode, Set<String> boundCodes) {
        String expectedName = BankSemanticRegistry.organizations().get(expectedCode).name();
        return ERROR_CODE + ": 题面唯一命中机构 " + expectedCode + "（" + expectedName
                + "），但 plan 绑定了 [" + String.join(", ", boundCodes)
                + "]；organizations 必须绑定题面逐字出现的目录机构，请改为 [" + expectedCode + "]";
    }
}
