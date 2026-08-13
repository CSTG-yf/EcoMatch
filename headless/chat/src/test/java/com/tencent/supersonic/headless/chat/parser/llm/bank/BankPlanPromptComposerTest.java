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
    void requirementsUserNamesTheStageWithoutRepeatingTheCatalog() {
        String content = BankPlanPromptComposer.buildRequirementsUserContent("存款是多少？");

        assertTrue(content.contains("<stage>REQUIREMENTS</stage>"));
        assertFalse(content.contains("可填写值目录"));
        assertFalse(content.contains(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX.substring(0, 20)));
    }

    @Test
    void planAndToolRepairCarryTheValidatedRequirementsContract() {
        String requirements = "{\"version\":\"1.0\",\"action\":\"EXECUTE\"}";
        String plan = BankPlanPromptComposer.buildPlanUserContent("存款是多少？", requirements);
        BankPlanToolResult failure = BankPlanToolResult.failed(2, "trace-2", "fingerprint-1",
                BankPlanToolResult.Stage.COMPILE, "UNSUPPORTED_PLAN_COMBINATION",
                Map.of("intent", List.of("POINT_QUERY", "CHANGE")), List.of("重新选择查询组合"));
        String repair = BankPlanPromptComposer.buildToolRepairUserContent("存款是多少？", requirements,
                "{\"intent\":\"POINT_QUERY\"}", failure);

        assertTrue(plan.contains("<stage>PLAN</stage>"));
        assertTrue(plan.contains("<requirements_contract>"));
        assertTrue(repair.contains("<tool_result>"));
        assertTrue(repair.contains("UNSUPPORTED_PLAN_COMBINATION"));
        assertTrue(repair.contains("<requirements_contract>"));
        assertFalse(repair.toUpperCase().contains("SELECT "));
    }

    @Test
    void systemPrefixStatesBothExactOutputFormatsWithoutHiddenBusinessRecipes() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("第一阶段：REQUIREMENTS 的精确输出格式"));
        assertTrue(sys.contains("BankRequestContract"));
        assertTrue(sys.contains("metricCodes"));
        assertTrue(sys.contains("answerFactTypes"));
        assertTrue(sys.contains("第二阶段：PLAN 的精确输出格式"));
        assertTrue(sys.contains("BankQueryPlan"));
        assertTrue(sys.contains("\"action\":\"EXECUTE\""));
        assertTrue(sys.contains("YYYY-MM-DD"));
        assertTrue(sys.contains("权威语义目录"));
        assertFalse(sys.contains("{{SEMANTIC_REGISTRY}}"));
        assertFalse(sys.contains("意图配方"));
        assertFalse(sys.contains("对公存款」=ZB003"));
        assertTrue(sys.contains("封闭指标清单"));
        assertTrue(sys.contains("不得用全目录代替理解结果"));
        assertTrue(sys.contains("包括目录中的字母城市占位名称"));
        assertTrue(sys.contains("不得依据系统当前日期"));
        assertTrue(sys.contains("只有没有目录精确命中或确实命中多个机构时才澄清"));
        assertTrue(sys.contains("均值排名"));
        assertTrue(sys.contains("aggregation\":\"AVG\""));
        assertTrue(sys.contains("rank_from_bottom"));
        assertTrue(sys.contains("limit 设为 2*N"));
        assertTrue(sys.contains("单日最高值和单日最低值出现在哪家"));
        assertTrue(sys.contains("AGGREGATION"));
        assertTrue(sys.contains("不要把它误判成普通 RANKING"));
        assertTrue(sys.contains("从基期到当前期的增幅排名前N"));
        assertTrue(sys.contains("这些结果别名"));
        assertTrue(sys.contains("写入 output.columns"));
        assertTrue(sys.contains("baselineEndDate 必须早于 startDate"));
        assertTrue(sys.contains("“较年初”必须使用 comparison=START_OF_YEAR"));
        assertTrue(sys.contains("当前期前一年的 12-31"));
        assertTrue(sys.contains("comparison 只要不是 NONE"));
        assertTrue(sys.contains("calculation.type 必须为 CHANGE"));
        assertTrue(sys.contains("CHANGE 的结果排序由编译器负责"));
        assertTrue(sys.contains("orderBy 必须为 []"));
        assertTrue(sys.contains("dimensions 必须为 [\"bank_organization\"]"));
        assertTrue(sys.contains("有多少天高于全省均值"));
        assertTrue(sys.contains("COUNT_DAYS_ABOVE_PROVINCE_AVERAGE"));
        assertTrue(sys.contains("DAYS_ABOVE_AVERAGE"));
        assertTrue(sys.contains("不得先对全年求和或平均后只比较一次"));
        assertTrue(sys.contains("只改正 error 指出的非法槽位"));
    }

    @Test
    void systemPrefixMakesExecutableFormatsUnambiguousToTheModel() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("尖括号中的内容只是占位说明，绝不可原样输出"));
        assertTrue(sys.contains("\"action\":\"EXECUTE\""));
        assertTrue(sys.contains("\"action\":\"CLARIFY\""));
        assertTrue(sys.contains("只选择回答当前问题实际需要的类型"));
        assertTrue(sys.contains("不得把所有枚举值都填入 answerFactTypes"));
        assertTrue(sys.contains("只问“变动了多少/增加或减少多少”"));
        assertTrue(sys.contains("\"answerFactTypes\":[\"CHANGE_VALUE\"]"));
        assertTrue(sys.contains("COMPARISON_VALUE 仅表示布尔阈值结论"));
        assertTrue(sys.contains("普通“与全省均值逐项对比”"));
        assertTrue(sys.contains("“高于/低于全省均值多少”只要求目标值和差额时"));
        assertTrue(sys.contains("只有用户明确询问“全省均值是多少/均值为多少”时"));
        assertTrue(sys.contains("“主要经营指标及排名”“各项指标及排名”表示同时列出每项指标当前值和全省排名"));
        assertTrue(sys.contains("VALUE、GAP_VALUE"));
        assertTrue(sys.contains("answerFactTypes 必须精确写成 [\"VALUE\",\"GAP_VALUE\"]"));
        assertTrue(sys.contains("\"answerFactTypes\":[\"VALUE\"]"));
        assertTrue(sys.contains("只有用户明确询问整体/总体趋势"));
        assertTrue(sys.contains("逐期数值的最高/最低由 VALUE 事实直接支撑"));
        assertTrue(sys.contains("最高日/最低日”不是 answerFactTypes 枚举"));
        assertTrue(sys.contains("禁止填写 MINIMUM_VALUE、MAXIMUM_VALUE"));
        assertTrue(sys.contains("若查询结果无法形成确定的整体方向"));
        assertTrue(sys.contains("的 answerFactTypes 应为 [\"VALUE\",\"TREND_DIRECTION\"]"));
        assertTrue(sys.contains("派生指标（代码、公式、单位与方向）"));
        assertTrue(sys.contains("DERIVED_ZB002_DIV_ZB001"));
        assertTrue(sys.contains("CHANGE 变化查询"));
        assertTrue(sys.contains("\"field\":\"benchmark\",\"operator\":\"COMPARE\","
                + "\"value\":\"PROVINCE_AVERAGE\",\"values\":[]"));
        assertTrue(sys.contains("全省排名不等于全省均值比较"));
        assertTrue(sys.contains("\"intent\":\"RANKING\""));
        assertTrue(sys.contains("\"filters\":[]"));
        assertTrue(sys.contains("明确给出目录中的具体指标、合法日期或日期范围"));
        assertTrue(sys.contains("必须 action=EXECUTE"));
        assertTrue(sys.contains("只有指标、机构或时间确实无法从权威目录和题干唯一确定时"));
        assertTrue(sys.contains("同一查询的两个结果事实"));
        assertTrue(sys.contains("不要因为出现“排名”就改成 RANKING"));
        assertTrue(sys.contains("封闭清单中的每一项都能映射到权威目录"));
        assertTrue(sys.contains("不得再以“请明确具体指标、机构和时间范围”之类的口径确认替代执行"));
        assertTrue(sys.contains("待评价指标集合"));
        assertTrue(sys.contains("只执行其中明确且可映射的目录指标"));
        assertTrue(sys.contains("日均值是多少？最高日和最低日"));
        assertTrue(sys.contains("aggregate_value、min_value、max_value、observation_count"));
        assertTrue(sys.contains("逐季变化/各季度末数值"));
        assertTrue(sys.contains("不得因没有目标机构而澄清"));
        assertTrue(sys.contains("REQUIREMENTS 的 intent 必须为 CHANGE"));
        assertTrue(sys.contains("“收入结构”是权威目录定义的复合业务语义"));
        assertTrue(sys.contains("中间业务收入（ZB007）"));
        assertTrue(sys.contains("净利息收入（ZB008）"));
        assertTrue(sys.contains("分类标签与其右侧指标列表构成封闭映射"));
        assertTrue(sys.contains("必须按自然季度末解析"));
        assertTrue(sys.contains("明确日期与全省均值比较"));
        assertTrue(sys.contains("不得因为“各项指标”或维度名称擅自加入映射之外的目录指标"));
        assertTrue(sys.contains("封闭映射解析算法"));
        assertTrue(sys.contains("每个代码都可回指"));
        assertTrue(sys.contains("不得扩展到全目录"));
        assertTrue(sys.contains("最高优先级执行合同"));
        assertTrue(sys.contains("用户显式枚举了非空的封闭指标清单"));
        assertTrue(sys.contains("每个输出代码必须能回指清单中的原始短语"));
        assertTrue(sys.contains("底层事实按日存储"));
        assertTrue(sys.contains("确定性点值比率例外"));
        assertTrue(sys.contains("metrics 必须按“分子、分母”"));
        assertTrue(sys.contains("calculation.type=RATIO"));
        assertTrue(sys.contains("某机构在全省13家里排第几"));
        assertTrue(sys.contains("不得在修复时删除目标机构"));
        assertTrue(sys.contains("截至YYYY-MM-DD"));
        assertTrue(sys.contains("不得截断成 YYYY-MM"));
        assertTrue(sys.contains("某机构在YYYY-MM-DD的存贷比是多少"));
        assertTrue(sys.contains("必须 action=EXECUTE 且 intent=RATIO"));
        assertTrue(sys.contains("answerFactTypes=[\"RATIO_VALUE\"]"));
        assertTrue(sys.contains("requirements_contract.organizationCodes 非空"));
        assertTrue(sys.contains("organizations 必须逐项保留这些机构"));
        assertTrue(sys.contains("output.columns"));
        assertTrue(sys.contains("禁止返回通用澄清文案"));
    }

    @Test
    void systemPrefixKeepsQuarterlySeriesOutOfPointToPointChangePlans() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("逐季变化”必须使用 intent=TREND"));
        assertTrue(sys.contains("time.comparison=NONE"));
        assertTrue(sys.contains("dimensions 必须包含 \"bank_data_date\""));
        assertTrue(sys.contains("calculation.type=DIRECT"));
        assertTrue(sys.contains("不得压缩为起点与终点的 CHANGE"));
    }

    @Test
    void systemPrefixDistinguishesCompositionSharesThresholdCountsAndDifferences() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("对公和个人分别占比"));
        assertTrue(sys.contains("分母必须是各项存款余额"));
        assertTrue(sys.contains("不要把两个构成项互相相除"));
        assertTrue(sys.contains("有多少家农商行"));
        assertTrue(sys.contains("intent=THRESHOLD"));
        assertTrue(sys.contains("必须保留 meets_condition"));
        assertTrue(sys.contains("比另一个指标高多少"));
        assertTrue(sys.contains("绝对差值"));
        assertTrue(sys.contains("不要使用 calculation.type=RATIO"));
    }

    @Test
    void rejectsCatalogDumpInUserContent() {
        String catalog = "可填写值目录（只能从下列内容中选择）：\n- /metrics/*/bizName: [ZB001]";
        assertThrows(IllegalArgumentException.class,
                () -> BankPlanPromptComposer.buildDynamicUserContent(catalog));
    }

    @Test
    void clarificationRepairIncludesGenericSlotNormalizationGuidance() {
        String content = BankPlanPromptComposer.buildRequirementsRepairUserContent(
                "从2024年末到2026-03-31，全省各项存款余额增幅排名前三的是哪几家？增幅各是多少？", "{\"action\":\"CLARIFY\"}",
                "model selected CLARIFY");

        assertTrue(content.contains("<clarification_recheck>"));
        assertTrue(content.contains("baselineStartDate=2024-12-31"));
        assertTrue(content.contains("organizationCodes=[]"));
        assertTrue(content.contains("ZB001"));
        assertTrue(content.contains("ZB002"));
        assertTrue(content.contains("ZB011"));
        assertTrue(content.contains("只能保留 CLARIFY"));
        assertTrue(content.contains("季度末"));
        assertTrue(content.contains("哪个季度最高"));
        assertTrue(content.contains("用户显式枚举非空目录指标"));
        assertTrue(content.contains("不能触发 CLARIFY"));
        assertTrue(content.contains("不得加入清单之外的任何 ZB###"));
        assertTrue(content.contains("禁止 MONTH"));
    }
}
