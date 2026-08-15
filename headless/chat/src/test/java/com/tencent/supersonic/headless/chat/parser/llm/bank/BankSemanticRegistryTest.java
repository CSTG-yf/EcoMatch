package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankSemanticRegistryTest {

    @Test
    void registryCoversEveryPlanEnumAndOfficialEntity() {
        assertEquals(enumNames(BankQueryPlan.PlanAction.class), BankSemanticRegistry.planActions());
        assertEquals(
                Arrays.stream(BankIntentType.values())
                        .filter(value -> value != BankIntentType.UNKNOWN).map(Enum::name)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                BankSemanticRegistry.intents());
        assertEquals(enumNames(BankQueryPlan.Aggregation.class),
                BankSemanticRegistry.aggregations());
        assertEquals(enumNames(BankQueryPlan.TimeGranularity.class),
                BankSemanticRegistry.timeGranularities());
        assertEquals(enumNames(BankQueryPlan.TimeComparison.class),
                BankSemanticRegistry.timeComparisons());
        assertEquals(enumNames(BankQueryPlan.CalculationType.class),
                BankSemanticRegistry.calculationTypes());
        assertEquals(enumNames(BankQueryPlan.SortDirection.class),
                BankSemanticRegistry.sortDirections());
        assertEquals(BankFinancialLexicon.metrics().keySet(), BankSemanticRegistry.metricCodes());
        assertEquals(BankFinancialLexicon.derivedMetrics().keySet(),
                BankSemanticRegistry.derivedMetricCodes());
        assertEquals(BankFinancialLexicon.organizations().keySet(),
                BankSemanticRegistry.organizationCodes());
    }

    @Test
    void promptAndJsonSchemaExposeTheSameRegistryValues() throws Exception {
        String prompt = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;
        String schema = BankQueryPlan.JSON_SCHEMA;

        BankSemanticRegistry.schemaEnumValues().forEach((field, values) -> values.forEach(value -> {
            assertTrue(prompt.contains(value), () -> field + " missing from prompt: " + value);
            assertTrue(schema.contains("\"" + value + "\""),
                    () -> field + " missing from JSON schema: " + value);
        }));
        assertTrue(prompt.contains("HALF_YEAR"));
        assertTrue(prompt.contains("filter field/operator/value contract"));
        assertTrue(schema.contains("\"action\""));
        assertTrue(schema.contains("\"type\":[\"integer\",\"null\"]"));

        JsonNode root = new ObjectMapper().readTree(schema);
        assertTrue(root.isObject());
        assertTrue(root.path("required").toString().contains("\"filters\""));
        assertTrue(root.path("required").toString().contains("\"limit\""));
        assertEquals(21, root.path("properties").path("metrics").path("items")
                .path("properties").path("bizName").path("enum").size());
    }

    @Test
    void outputFactCatalogIsCompleteAndDescriptive() {
        Set<String> expected =
                Set.of("METRIC_VALUE", "CURRENT_VALUE", "BASELINE_VALUE", "ABSOLUTE_CHANGE",
                        "PERCENT_CHANGE", "VALUE_DIFFERENCE", "RATIO_PERCENT", "RANK_POSITION",
                        "PROVINCIAL_AVERAGE", "DAYS_ABOVE_AVERAGE", "TOTAL_DAYS", "DAILY_AVERAGE",
                        "MINIMUM_VALUE", "MAXIMUM_VALUE", "COMPARISON_TYPE", "METRIC_ROLE");

        assertEquals(expected, BankSemanticRegistry.outputFacts().keySet());
        BankSemanticRegistry.outputFacts().forEach((name, fact) -> {
            assertFalse(fact.description().isBlank(), name);
            assertFalse(fact.dataType().isBlank(), name);
            assertFalse(fact.outputColumn().isBlank(), name);
            assertFalse(fact.supportedIntents().isEmpty(), name);
        });
    }

    @Test
    void netProfitUsesTheOfficialGoldUnit() {
        assertEquals("万元", BankSemanticRegistry.metrics().get("ZB011").unit());
        assertTrue(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX.contains("ZB011 净利润"));
        assertTrue(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX.contains("unit=万元"));
    }

    @Test
    void netProfitMarginIsPublishedToTheModelAsADerivedMetric() {
        assertTrue(BankSemanticRegistry.derivedMetricCodes().contains("DERIVED_ZB011_DIV_ZB009"));
        assertTrue(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX
                .contains("DERIVED_ZB011_DIV_ZB009 净利润率（formula=ZB011 / ZB009"));
    }

    @Test
    void sharedCatalogListsEveryMetricDerivedAndOrganizationExactlyOnce() {
        String shared = BankSemanticRegistry.sharedCatalog();

        BankSemanticRegistry.metrics().forEach((code, metric) -> {
            String declaration = code + " " + metric.name() + "（aliases=";
            assertTrue(shared.contains(declaration), code + " missing from shared catalog");
            assertEquals(shared.indexOf(declaration), shared.lastIndexOf(declaration),
                    code + " must be declared exactly once");
        });
        BankSemanticRegistry.derivedMetrics().forEach((code, metric) -> {
            String declaration = code + " " + metric.name() + "（formula=";
            assertTrue(shared.contains(declaration), code + " missing from shared catalog");
            assertEquals(shared.indexOf(declaration), shared.lastIndexOf(declaration),
                    code + " must be declared exactly once");
        });
        BankSemanticRegistry.organizations().forEach((code, org) -> {
            String declaration = code + " " + org.name() + "（aliases=";
            assertTrue(shared.contains(declaration), code + " missing from shared catalog");
            assertEquals(shared.indexOf(declaration), shared.lastIndexOf(declaration),
                    code + " must be declared exactly once");
        });
        assertTrue(shared.contains("intent"));
        assertTrue(shared.contains("time granularity"));
        assertTrue(shared.contains("time comparison"));
        assertFalse(shared.contains("aggregation:"), "aggregation is PLAN-only");
        assertFalse(shared.contains("calculation type"), "calculation type is PLAN-only");
        assertFalse(shared.contains("plan fields"), "plan fields are PLAN-only");
        assertFalse(shared.contains("sort direction"), "sort direction is PLAN-only");
        assertFalse(shared.contains("输出事实"), "output facts are PLAN-only");
    }

    @Test
    void planCapabilityCatalogCarriesPlanOnlyEnumsAndOutputFacts() {
        String capabilities = BankSemanticRegistry.planCapabilityCatalog();

        BankSemanticRegistry.aggregations().forEach(
                value -> assertTrue(capabilities.contains(value), value + " missing"));
        BankSemanticRegistry.calculationTypes().forEach(
                value -> assertTrue(capabilities.contains(value), value + " missing"));
        BankSemanticRegistry.sortDirections().forEach(
                value -> assertTrue(capabilities.contains(value), value + " missing"));
        BankSemanticRegistry.filterOperators().forEach(
                value -> assertTrue(capabilities.contains(value), value + " missing"));
        BankSemanticRegistry.outputFacts().keySet().forEach(
                value -> assertTrue(capabilities.contains(value), value + " missing"));
        assertTrue(capabilities.contains("aggregation"));
        assertTrue(capabilities.contains("calculation type"));
        assertTrue(capabilities.contains("sort direction"));
        assertTrue(capabilities.contains("filter field/operator/value contract"));
        assertTrue(capabilities.contains("benchmark + COMPARE 仅允许 value=PROVINCE_AVERAGE"));
        assertFalse(capabilities.contains("unit=亿元"),
                "metric units stay in the shared catalog only");
    }

    @Test
    void filterContractIsTheCompleteSingleSourceForFilterFieldsAndOperators() {
        String contract = BankSemanticRegistry.filterContract();

        assertTrue(contract.contains("field categories"));
        assertTrue(contract.contains("operators"));
        BankSemanticRegistry.filterOperators().forEach(
                operator -> assertTrue(contract.contains(operator), operator + " missing"));
        BankSemanticRegistry.filterFields().forEach(
                field -> assertTrue(contract.contains(field), field + " missing"));
        assertFalse(contract.contains("calculation"),
                "filter contract must stay free of PLAN-only calculation rules");
        assertFalse(contract.contains("orderBy"),
                "filter contract must stay free of PLAN-only ordering rules");
        assertFalse(contract.contains("output.columns"),
                "filter contract must stay free of PLAN-only output rules");
    }

    @Test
    void nullableMetadataFieldsAcceptStringOrNullInSchemaAndPrompt() throws Exception {
        String schema = BankQueryPlan.JSON_SCHEMA;
        String prompt = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(schema.contains("\"alias\":{\"type\":[\"string\",\"null\"]}"));
        assertTrue(schema.contains("\"bizName\":{\"type\":[\"string\",\"null\"]}"));
        assertFalse(schema.contains("\"alias\":{\"type\":\"string\"}"),
                "alias must not be restricted to a plain string type");
        assertFalse(schema.contains("\"bizName\":{\"type\":\"string\"}"),
                "bizName must not be restricted to a plain string type");

        JsonNode root = new ObjectMapper().readTree(schema);
        JsonNode aliasType = root.path("properties").path("metrics").path("items")
                .path("properties").path("alias").path("type");
        JsonNode bizNameType = root.path("properties").path("organizations").path("items")
                .path("properties").path("bizName").path("type");
        assertArrayEquals(new String[] {"string", "null"}, toStrings(aliasType));
        assertArrayEquals(new String[] {"string", "null"}, toStrings(bizNameType));

        assertTrue(prompt.contains("\"alias\":null"),
                "canonical PLAN example must emit alias:null");
        assertTrue(prompt.contains("\"bizName\":null"),
                "canonical PLAN example must emit organization bizName:null");
    }

    private static String[] toStrings(JsonNode array) {
        String[] values = new String[array.size()];
        for (int i = 0; i < array.size(); i++) {
            values[i] = array.get(i).asText();
        }
        return values;
    }

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
