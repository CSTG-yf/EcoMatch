package com.tencent.supersonic.headless.core.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExplainCostPolicyTest {

    @Test
    void readsStructuredAndTextExplainPlans() {
        ExplainCostPolicy policy = new ExplainCostPolicy(100_000);

        assertEquals(1200, policy.validate(List.of(Map.of("rows", 1200))));
        assertEquals(21000, policy.validate(
                List.of(Map.of("QUERY PLAN", "Seq Scan (cost=0.00..431.00 rows=21000 width=8)"))));
    }

    @Test
    void rejectsPlansAboveConfiguredCeiling() {
        ExplainCostPolicy policy = new ExplainCostPolicy(1000);

        assertThrows(QueryRejectedException.class,
                () -> policy.validate(List.of(Map.of("Plan_Rows", 1001))));
    }

    @Test
    void readsNestedJsonExplainPlans() {
        ExplainCostPolicy policy = new ExplainCostPolicy(100_000);
        Map<String, Object> nestedPlan = Map.of("Plan", Map.of("Node Type", "Hash Join",
                "Plan Rows", 42_000, "Plans", List.of(Map.of("Plan Rows", 12_000))));

        assertEquals(42_000, policy.validate(List.of(Map.of("QUERY PLAN", nestedPlan))));
        assertEquals(31_000, policy
                .validate(List.of(Map.of("QUERY PLAN", "[{\"Plan\":{\"Plan Rows\":31000}}]"))));
        assertEquals(7_501, policy.validate(List.of(Map.of("estimated-rows", "7500.1"))));
    }

    @Test
    void canFailClosedWhenExplainDoesNotExposeAnEstimate() {
        ExplainCostPolicy policy = new ExplainCostPolicy(100_000, true);

        assertThrows(QueryRejectedException.class,
                () -> policy.validate(List.of(Map.of("QUERY PLAN", "constant result"))));
        assertEquals(0, policy.validate(List.of(Map.of("Plan Rows", 0))));
    }
}
