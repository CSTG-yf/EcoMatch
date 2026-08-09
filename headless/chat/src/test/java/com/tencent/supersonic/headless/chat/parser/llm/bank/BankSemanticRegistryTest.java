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

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
