package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPlanPromptComposerTest {

    @Test
    void dynamicUserIsQuestionOnly() {
        String question = "江苏省A市农商行在2025年6月15日，各项存款余额是多少？";
        assertEquals(question, BankPlanPromptComposer.buildDynamicUserContent(question));
        assertFalse(BankPlanPromptComposer.looksLikeCatalogDump(question));
    }

    @Test
    void rejectsCatalogDumpInUserContent() {
        String catalog = "可填写值目录（只能从下列内容中选择）：\n- /metrics/*/bizName: [ZB001]";
        assertThrows(IllegalArgumentException.class,
                () -> BankPlanPromptComposer.buildDynamicUserContent(catalog));
    }

    @Test
    void repairUserDoesNotRestateFieldCatalog() {
        String repair = BankPlanPromptComposer.buildRepairUserContent("存款是多少？",
                "{\"intent\":\"POINT_QUERY\"}", "output columns invalid");
        assertTrue(repair.startsWith("存款是多少？"));
        assertTrue(repair.contains("<repair>"));
        assertFalse(repair.contains("可填写值目录"));
        assertFalse(repair.contains("Metrics=["));
        assertFalse(repair.contains(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX.substring(0, 20)));
    }

    @Test
    void repairStripsEmbeddedCatalogFromError() {
        String repair = BankPlanPromptComposer.buildRepairUserContent("q", "{}",
                "bad\n可填写值目录\n- /metrics/*/bizName: [ZB001]");
        assertTrue(repair.contains("plan validation failed"));
        assertFalse(repair.contains("可填写值目录"));
    }

    @Test
    void toolRepairContainsOnlyPreviousPlanAndSanitizedStageFailure() {
        BankPlanToolResult failure = BankPlanToolResult.failed(2, "trace-2", "fingerprint-1",
                BankPlanToolResult.Stage.COMPILE, "UNSUPPORTED_PLAN_COMBINATION",
                Map.of("intent", List.of("POINT_QUERY", "CHANGE")),
                List.of("重新选择受支持的查询族"));

        String repair = BankPlanPromptComposer.buildToolRepairUserContent("存款变化多少？",
                "{\"intent\":\"CHANGE\"}", failure);

        assertTrue(repair.startsWith("存款变化多少？"));
        assertTrue(repair.contains("<tool_result>"));
        assertTrue(repair.contains("UNSUPPORTED_PLAN_COMBINATION"));
        assertTrue(repair.contains("<previous_plan>"));
        assertTrue(repair.contains("必须输出修正后的完整 BankQueryPlan"));
        assertFalse(repair.toUpperCase().contains("SELECT "));
        assertFalse(repair.contains("gold"));
    }

    @Test
    void systemPrefixTeachesRecipesWithoutLeakingEvalQuestions() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;
        // Abstract recipes stay.
        assertTrue(sys.contains("待评价指标集合"));
        assertTrue(sys.contains("derivedMetrics"));
        assertTrue(sys.contains("COUNT_DAYS_ABOVE_PROVINCE_AVERAGE"));
        assertTrue(sys.contains("结构骨架"));
        // Must not embed concrete train/dev-style natural-language questions as few-shots.
        assertFalse(sys.contains("江苏省A市农商行在2025年6月15日"));
        assertFalse(sys.contains("待评价指标集合：存贷比、不良率、拨备覆盖率"));
        assertFalse(sys.contains("规模（贷款）、质量（不良率）、效益（净利润）"));
        assertFalse(sys.contains("不良率和全省均值比怎么样"));
        assertTrue(BankPlanPromptComposer.PREFIX_VERSION.contains("v10"));
    }
}
