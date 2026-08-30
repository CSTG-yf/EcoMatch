package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPlanToolResultTest {

    @Test
    void exposesTheEightStableExecutionStages() {
        assertEquals(List.of("PLAN_SCHEMA", "PLAN_SEMANTIC", "COMPILE", "TRANSLATE",
                        "SQL_SAFETY", "DATABASE_PREPARE", "DATABASE_EXECUTE", "RESULT_SEMANTIC"),
                List.of(BankPlanToolResult.Stage.values()).stream().map(Enum::name).toList());
    }

    @Test
    void translationFailureCarriesRootCauseHintsThroughRepairFeedback() {
        BankPlanToolResult result = BankPlanToolResult.started(1, "trace-t1", null,
                        "S2SQL_TEMPLATE", List.of("bank_organization", "ZB001"))
                .fail(BankPlanToolResult.Stage.TRANSLATE, "TRANSLATION_FAILED", Map.of(),
                        List.of("failed_layer=CALCITE_VALIDATE", "root_type=CalciteContextException",
                                "root_message=No match found for function rank_over"));

        assertEquals(BankPlanToolResult.Status.FAILED, result.getStatus());
        assertEquals(BankPlanToolResult.Stage.TRANSLATE, result.getFailedStage());
        assertEquals("TRANSLATION_FAILED", result.getErrorCode());
        assertEquals("语义翻译或物理编译失败，请按根因提示修正完整计划。", result.getMessage());
        String feedback = result.toRepairFeedback();
        assertTrue(feedback.contains("\"failedStage\":\"TRANSLATE\""));
        assertTrue(feedback.contains("CALCITE_VALIDATE"));
        assertFalse(feedback.toLowerCase().contains("select "));
    }

    @Test
    void buildsSanitizedFailureWithoutSqlGoldOrThrowableDetails() {
        BankPlanToolResult result = BankPlanToolResult.started(2, "trace-2", "fingerprint-1",
                "S2SQL_TEMPLATE", List.of("current_value", "percent_change"))
                .fail(BankPlanToolResult.Stage.DATABASE_EXECUTE, "JDBC_GRAMMAR",
                        Map.of("metrics", List.of("ZB001", "ZB002")),
                        List.of("重新选择合法指标并生成完整计划"));

        assertEquals(BankPlanToolResult.Status.FAILED, result.getStatus());
        assertEquals(BankPlanToolResult.Stage.DATABASE_EXECUTE, result.getFailedStage());
        assertEquals("JDBC_GRAMMAR", result.getErrorCode());
        assertEquals("数据库执行失败，请根据允许值修正完整计划。", result.getMessage());
        assertEquals("fingerprint-1", result.getPreviousPlanFingerprint());
        assertEquals(List.of("ZB001", "ZB002"), result.getAllowedValues().get("metrics"));
        assertTrue(result.getStageResults().stream().anyMatch(stage -> stage.getStage()
                == BankPlanToolResult.Stage.DATABASE_EXECUTE
                && stage.getStatus() == BankPlanToolResult.StageStatus.FAILED));
        String serialized = result.toRepairFeedback();
        assertTrue(serialized.contains("JDBC_GRAMMAR"));
        assertFalse(serialized.toLowerCase().contains("select "));
        assertFalse(serialized.contains("gold"));
        assertFalse(serialized.contains("Throwable"));
    }

    @Test
    void completesWithBoundedResultPreviewAndWithoutChangingInputRows() {
        List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("metric_code", "ZB001");
                    row.put("metric_value", index);
                    return row;
                }).toList();

        BankPlanToolResult result = BankPlanToolResult.started(1, "trace-1", null, "STRUCT",
                List.of("metric_code", "metric_value"))
                .succeed(BankPlanToolResult.Stage.SQL_SAFETY)
                .succeed(BankPlanToolResult.Stage.DATABASE_PREPARE)
                .succeed(BankPlanToolResult.Stage.DATABASE_EXECUTE)
                .complete(List.of("metric_code", "metric_value"), rows);

        assertEquals(BankPlanToolResult.Status.SUCCEEDED, result.getStatus());
        assertNull(result.getFailedStage());
        assertEquals(5, result.getResultPreview().size());
        assertEquals(8, rows.size());
        assertEquals(List.of("metric_code", "metric_value"), result.getResultSchema());
        assertEquals(BankPlanToolResult.Stage.RESULT_SEMANTIC,
                result.getStageResults().get(result.getStageResults().size() - 1).getStage());
    }
}
