package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankContractJsonSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requirementsSchemaIsStrictAndUsesOnlyPublishedContractValues() throws Exception {
        JsonNode schema = MAPPER.readTree(BankRequestContract.JSON_SCHEMA);

        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(Set.of("version", "action", "intent", "metricCodes", "derivedMetrics",
                "organizationCodes", "time", "filters", "requiredLimit", "answerFactTypes",
                "clarification"), textSet(schema.path("required")));
        assertEquals(Set.of("EXECUTE", "CLARIFY"),
                textSet(schema.path("properties").path("action").path("enum")));
        assertTrue(textSet(schema.path("properties").path("intent").path("enum"))
                .contains("UNKNOWN"), "CLARIFY must retain the explicit UNKNOWN intent shape");
        assertEquals(BankSemanticRegistry.metricCodes(), textSet(schema.path("properties")
                .path("metricCodes").path("items").path("enum")));
        assertEquals(BankSemanticRegistry.filterOperators(), textSet(schema.path("properties")
                .path("filters").path("items").path("properties").path("operator").path("enum")));
        assertEquals("^\\d{4}-\\d{2}-\\d{2}$", schema.path("properties").path("time")
                .path("properties").path("startDate").path("pattern").asText());
        assertEquals(1, schema.path("properties").path("requiredLimit").path("minimum").asInt());
    }

    @Test
    void planSchemaKeepsEveryModelOwnedCollectionAndDatePatternStrict() throws Exception {
        JsonNode schema = MAPPER.readTree(BankQueryPlan.JSON_SCHEMA);

        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertTrue(textSet(schema.path("required")).contains("derivedMetrics"));
        assertEquals("^\\d{4}-\\d{2}-\\d{2}$", schema.path("properties").path("time")
                .path("properties").path("endDate").path("pattern").asText());
        assertEquals(1, schema.path("properties").path("limit").path("minimum").asInt());
        assertEquals(BankSemanticRegistry.filterFields(), textSet(schema.path("properties")
                .path("filters").path("items").path("properties").path("field").path("enum")));
    }

    private static Set<String> textSet(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText)
                .collect(Collectors.toSet());
    }
}
