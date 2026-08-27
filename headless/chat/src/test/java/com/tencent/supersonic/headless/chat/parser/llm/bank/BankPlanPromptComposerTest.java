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
        String question = "江苏省M市农商行在2026年2月18日的各项存款余额是多少？";
        assertEquals(question, BankPlanPromptComposer.buildDynamicUserContent(question));
        assertFalse(BankPlanPromptComposer.looksLikeCatalogDump(question));
    }

    @Test
    void fewShotIsInjectedIntoDynamicContentOnlyWhenExplicitlyEnabled() {
        String question = "请比较某机构某指标的环比和同比变化";
        String examples = BankFewShotExemplarCatalog.renderRequirementsExamples(question);

        String disabled = BankPlanPromptComposer.buildRequirementsUserContent(question, null);
        String legacy = BankPlanPromptComposer.buildRequirementsUserContent(question);
        String enabled = BankPlanPromptComposer.buildRequirementsUserContent(question, examples);

        assertEquals(legacy, disabled);
        assertTrue(enabled.contains("<family_examples>"));
        assertFalse(BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX
                .contains("<family_examples>"));
        assertFalse(BankPlanPromptComposer.PLAN_SYSTEM_PREFIX
                .contains("<family_examples>"));
        assertTrue(enabled.indexOf("<family_examples>")
                < enabled.indexOf("<stage>REQUIREMENTS</stage>"));
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

        assertTrue(sys.contains("唯一正常阶段：SINGLE_PASS"));
        assertTrue(sys.contains("不得等待第二次调用"));
        assertTrue(sys.contains("BankRequestContract"));
        assertTrue(sys.contains("metricCodes"));
        assertTrue(sys.contains("answerFactTypes"));
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
        assertTrue(sys.contains("哪家农商行/机构的某指标最高、最低、最多、最少"));
        assertTrue(sys.contains("直接指标的 RANKING 必须填写一个合法的 orderBy"));
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
        assertTrue(sys.contains("评测事实库中的日粒度计数"));
        assertTrue(sys.contains("COUNT_DAYS_ABOVE_PROVINCE_AVERAGE"));
        assertTrue(sys.contains("DAYS_ABOVE_AVERAGE"));
        assertTrue(sys.contains("不得先对全年求和或平均后只比较一次"));
        assertTrue(sys.contains("这个专用 calculation 只表示“高于”"));
        assertTrue(sys.contains("不得把“低于/小于”伪装成同一查询族"));
        assertTrue(sys.contains("只改正 error 指出的非法槽位"));
        assertTrue(sys.contains("绝对阈值判断的精确计划合同"));
        assertTrue(sys.contains("\"intent\":\"THRESHOLD\""));
        assertTrue(sys
                .contains("\"field\":\"metric_value\",\"operator\":\"GT 或 GTE 或 LT 或 LTE 或 EQ\""));
        assertTrue(sys.contains("dimensions 必须精确为 [\"bank_organization\"]"));
        assertTrue(sys.contains("output.columns 必须精确为 [\"bank_organization\",\"<所选 ZB###>\"]"));
        assertTrue(sys.contains("排最后一名/倒数第一"));
        assertTrue(sys.contains("operator=LTE、value=\"1\"、values=[]"));
        assertTrue(sys.contains("对公存款加个人存款是否等于各项存款/差额多少"));
        assertTrue(sys.contains("answerFactTypes=[\"VALUE\",\"GAP_VALUE\"]"));
        assertTrue(sys.contains("不能套 COMPARISON 或 RATIO"));
    }

    @Test
    void promptsCarryExactMonthAndYearAndProvinceBottomContracts() {
        String requirements = BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX;
        String plan = BankPlanPromptComposer.PLAN_SYSTEM_PREFIX;

        assertTrue(requirements.contains("time.comparison=MOM_AND_YOY"));
        assertTrue(requirements.contains("恰好一个 organizationCodes 和一个 metricCodes"));
        assertTrue(requirements.contains("rank_from_bottom/LTE/N"));
        assertTrue(requirements.contains("哪家农商行/机构的某指标最高、最低、最多、最少"));
        assertTrue(requirements.contains("对公存款加个人存款是否等于各项存款/差额多少"));
        assertTrue(plan.contains("恰好一个 organizations"));
        assertTrue(plan.contains("编译器会派生上月末和去年同期两个基期"));
        assertTrue(plan.contains("不得把“哪家”当作缺槽位"));
        assertTrue(plan.contains("organizations=[]"));
        assertTrue(plan.contains("output.columns=[\"bank_organization\",\"ZB003"));
        assertTrue(plan.contains("\"ZB004\",\"ZB001\"]"));
    }

    @Test
    void requirementsPrefixDisambiguatesExplicitYearEndAndSelectedBestComparison() {
        String requirements = BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX;

        assertTrue(requirements.contains("明确写出的 YYYY年末/年底就是该年份的 12-31"));
        assertTrue(requirements.contains("只有“较上年末/较去年末”这类相对表述才按当前期前一自然年年末解释"));
        assertTrue(requirements.contains("已列出的多家机构中问“谁/哪家最好/最优/控制得最好/表现最好”"));
        assertTrue(requirements.contains("intent=COMPARISON"));
        assertTrue(requirements.contains("[\"VALUE\",\"GAP_VALUE\"]"));
        assertTrue(requirements.contains("不得退化为 RANKING"));
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
        assertTrue(sys.contains("机构间普通比较与局部排名必须严格区分"));
        assertTrue(sys.contains("机构A比机构B多多少/少多少/相差多少"));
        assertTrue(sys.contains("机构A、机构B、机构C三家谁/哪家某指标最多/最少/最高/最低"));
        assertTrue(sys.contains("不得把“谁最多”理解为 COMPARISON"));
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
        assertTrue(sys.contains("DERIVED_ZB011_DIV_ZB018"));
        assertTrue(sys.contains("对公和个人分别占比"));
        assertTrue(sys.contains("贷款结构双分项"));
        assertTrue(sys.contains("[\"ZB006\",\"ZB005\",\"ZB002\"]"));
        assertTrue(sys.contains("人均利润"));
        assertTrue(sys.contains("aggregationMode"));
        assertTrue(sys.contains("逾期贷款率"));
        assertTrue(sys.contains("全省排第几"));
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
        assertTrue(sys.contains("不要因为指标通常按季度披露"));
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
        assertTrue(sys.contains("某机构在全省里排第几"));
        assertTrue(sys.contains("limit 填全省机构总数"),
                "province rank position uses the catalog organization count, not a hardcoded 13");
        assertTrue(sys.contains("不得在修复时删除目标机构"));
        assertTrue(sys.contains("截至YYYY-MM-DD"));
        assertTrue(sys.contains("不得截断成 YYYY-MM"));
        assertTrue(sys.contains("某机构在YYYY-MM-DD的存贷比是多少"));
        assertTrue(sys.contains("必须 action=EXECUTE 且 intent=RATIO"));
        assertTrue(sys.contains("answerFactTypes=[\"RATIO_VALUE\"]"));
        assertTrue(sys.contains("requirements.organizationCodes 非空"));
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
    void systemPrefixDistinguishesProvinceAverageCountsAndAbsoluteRateDifferences() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("有多少家农商行"));
        assertTrue(sys.contains("逐机构阈值计数"));
        assertTrue(sys.contains("intent=THRESHOLD"));
        assertTrue(sys.contains("organizationCodes=[]"));
        assertTrue(sys.contains("必须保留 meets_condition"));
        assertTrue(sys.contains("同一时点两个基础指标的绝对差值"));
        assertTrue(sys.contains("不得使用 calculation.type=RATIO"));
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
                "请按2024年末至2026-02-28的增长幅度，列出全省各项存款余额排名最高的三家机构。", "{\"action\":\"CLARIFY\"}",
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

    @Test
    void requirementsSystemPrefixExcludesPlanOnlyRules() {
        String sys = BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX;

        assertTrue(sys.startsWith(BankPlanPromptComposer.COMMON_FACT_PREFIX));
        assertFalse(sys.contains("\"orderBy\""), "orderBy is a BankQueryPlan field");
        assertFalse(sys.contains("\"calculation\""), "calculation is a BankQueryPlan field");
        assertFalse(sys.contains("\"output\""), "output is a BankQueryPlan field");
        assertFalse(sys.contains("aggregationMode"), "aggregationMode is PLAN-only");
        assertFalse(sys.contains("COUNT_DAYS_ABOVE_PROVINCE_AVERAGE"));
        assertFalse(sys.contains("\"dimensions\""), "dimensions capability rules are PLAN-only");
        assertFalse(sys.contains("\"metrics\""));
        assertFalse(sys.contains("\"limit\""));
        assertFalse(sys.contains("\"aggregation\""));
    }

    @Test
    void requirementsSystemPrefixCarriesTheCompleteFilterContract() {
        String sys = BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX;
        String contract = BankSemanticRegistry.filterContract();

        assertTrue(sys.contains(contract),
                "REQUIREMENTS must render the registry filter contract");
        BankSemanticRegistry.filterOperators().forEach(operator -> assertTrue(sys.contains(operator),
                "REQUIREMENTS lost filter operator " + operator));
        BankSemanticRegistry.filterFields().forEach(field -> assertTrue(sys.contains(field),
                "REQUIREMENTS lost filter field category " + field));
        assertFalse(sys.contains("\"orderBy\""), "filter contract must not leak PLAN ordering");
        assertFalse(sys.contains("\"calculation\""), "filter contract must not leak PLAN calculation");
        assertFalse(sys.contains("\"output\""), "filter contract must not leak PLAN output");
    }

    @Test
    void planSystemPrefixExcludesRequirementsOnlyRules() {
        String sys = BankPlanPromptComposer.PLAN_SYSTEM_PREFIX;

        assertTrue(sys.startsWith(BankPlanPromptComposer.COMMON_FACT_PREFIX));
        // The action enum value itself (allowed=[EXECUTE, CLARIFY]) is shared data, but no CLARIFY
        // rule, trigger condition or instruction may leak into the PLAN stage.
        assertFalse(sys.contains("action=CLARIFY"));
        assertFalse(sys.contains("CLARIFY 时"));
        assertFalse(sys.contains("action=CLARIFY；"));
        assertFalse(sys.contains("返回 CLARIFY"));
        assertFalse(sys.contains("CLARIFY 的理由"));
        assertFalse(sys.contains("action=CLARIFY，"));
        assertFalse(sys.contains("answerFactTypes"));
        assertFalse(sys.contains("待评价指标集合"));
        assertFalse(sys.contains("封闭指标清单"));
        assertFalse(sys.contains("封闭映射"));
        assertFalse(sys.contains("澄清"), "PLAN must not carry CLARIFY guidance");
        assertTrue(sys.contains("requirements"), "PLAN reads the validated contract");
    }

    @Test
    void bothStagePrefixesShareTheSameRegistryGeneratedFacts() {
        String shared = BankSemanticRegistry.sharedCatalog();
        String planCapabilities = BankSemanticRegistry.planCapabilityCatalog();

        assertTrue(BankPlanPromptComposer.COMMON_FACT_PREFIX.contains(shared));
        assertTrue(BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX.contains(shared));
        assertTrue(BankPlanPromptComposer.PLAN_SYSTEM_PREFIX.contains(shared));
        assertTrue(BankPlanPromptComposer.PLAN_SYSTEM_PREFIX.contains(planCapabilities));
        assertFalse(BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX.contains(planCapabilities));
    }

    @Test
    void stagePrefixesKeepJsonFieldContractsCompatible() {
        String requirements = BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX;
        String plan = BankPlanPromptComposer.PLAN_SYSTEM_PREFIX;

        assertTrue(requirements.contains("\"metricCodes\""));
        assertTrue(requirements.contains("\"answerFactTypes\""));
        assertTrue(requirements.contains("\"requiredLimit\""));
        assertTrue(requirements.contains("\"clarification\""));
        assertTrue(requirements.contains("\"action\":\"CLARIFY\""));
        assertTrue(requirements.contains("\"action\":\"EXECUTE\""));
        assertTrue(requirements.contains("BankRequestContract"));

        assertTrue(plan.contains("\"calculation\""));
        assertTrue(plan.contains("\"orderBy\""));
        assertTrue(plan.contains("\"output\""));
        assertTrue(plan.contains("\"limit\""));
        assertTrue(plan.contains("\"dimensions\""));
        assertTrue(plan.contains("\"metrics\""));
        assertTrue(plan.contains("\"action\":\"EXECUTE\""));
        assertTrue(plan.contains("BankQueryPlan"));
    }

    @Test
    void repairUserContentStaysScopedToTheCurrentStage() {
        String requirementsRepair = BankPlanPromptComposer.buildRequirementsRepairUserContent(
                "存款是多少？", "{\"action\":\"CLARIFY\"}", "model selected CLARIFY");
        assertTrue(requirementsRepair.contains("<stage>REQUIREMENTS</stage>"));
        assertTrue(requirementsRepair.contains("<repair>"));
        assertTrue(requirementsRepair.contains("<previous_candidate>"));
        assertFalse(requirementsRepair.contains("\"orderBy\""));
        assertFalse(requirementsRepair.contains("\"calculation\""));

        String planRepair = BankPlanPromptComposer.buildPlanRepairUserContent("存款是多少？",
                "{\"version\":\"1.0\",\"action\":\"EXECUTE\"}", "{\"intent\":\"POINT_QUERY\"}",
                "required_metrics_missing: ZB001");
        assertTrue(planRepair.contains("<stage>PLAN</stage>"));
        assertTrue(planRepair.contains("<requirements_contract>"));
        assertTrue(planRepair.contains("required_metrics_missing"));
        assertFalse(planRepair.contains("answerFactTypes 的精确含义"));
        assertFalse(planRepair.contains("封闭指标清单"));
    }

    @Test
    void singlePassPrefixExposesBothNestedContractsWithoutASecondNormalCall() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("唯一正常阶段：SINGLE_PASS"));
        assertTrue(sys.contains("不得等待第二次调用"));
        assertTrue(sys.contains("完整 BankPlanningResponse"));
        assertFalse(sys.contains("仍只输出当前阶段要求的一份完整 BankRequestContract"));
        assertFalse(sys.contains("仍只输出当前阶段要求的一份完整 BankQueryPlan"));
        assertTrue(sys.contains(BankSemanticRegistry.sharedCatalog()));
        assertTrue(sys.contains(BankSemanticRegistry.planCapabilityCatalog()));
        assertTrue(sys.contains("BankRequestContract"));
        assertTrue(sys.contains("BankQueryPlan"));
    }

    @Test
    void singlePassRepairAlwaysRequestsTheCompleteEnvelope() {
        String repair = BankPlanPromptComposer.buildSinglePassRepairUserContent("存款是多少？",
                "{\"requirements\":{},\"plan\":{}}", "required_metrics_missing: ZB001", null);

        assertTrue(repair.contains("<stage>SINGLE_PASS</stage>"));
        assertTrue(repair.contains("只输出修正后的完整 BankPlanningResponse JSON"));
        assertFalse(repair.contains("只输出修正后的完整当前阶段 JSON"));
    }

    @Test
    void systemPrefixCarriesSharedDateBaselineRulesInBothStageContexts() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("环比基期与同比基期全部由编译器派生，禁止自行填写"),
                "MOM_AND_YOY baseline exemption must be stated first");
        assertTrue(sys.contains("baselineEndDate 必须早于 startDate"));
        assertTrue(sys.contains("绝不可把“从基期到当前期”误写成 startDate=基期、endDate=当前期"));
        assertTrue(sys.contains("当年 01-01 不是“较年初”基期"));
        int firstInjection = sys.indexOf("重要豁免先记住：time.comparison=MOM_AND_YOY");
        int secondInjection = sys.indexOf("重要豁免先记住：time.comparison=MOM_AND_YOY",
                firstInjection + 1);
        assertTrue(firstInjection >= 0 && secondInjection > firstInjection,
                "shared date/baseline rules must render into both REQUIREMENTS and PLAN sections");
    }

    @Test
    void systemPrefixLocksSliceLimitAggregationModeAndGranularityContracts() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("limit 的唯一合法取值来源是排名切片条款"));
        assertTrue(sys.contains("plan.limit=2*N"));
        assertTrue(sys.contains("禁止把机构总数当成 limit 填写"));
        assertTrue(sys.contains("必须填 \"AVERAGE_ONLY\""));
        assertTrue(sys.contains("必须填 \"WITH_EXTREMA\""));
        assertTrue(sys.contains("校验器按上述规则逐条核对"));
        assertTrue(sys.contains("否则一律 DAY（聚合周期语义不属于 granularity）"));
        assertTrue(sys.contains("逐字照抄权威目录中的中文名称，禁止自造别名"));
    }

    @Test
    void singlePassResponseSectionMapsEveryConsistencySlotBetweenContracts() {
        String sys = BankPlanPromptComposer.FIXED_SYSTEM_PREFIX;

        assertTrue(sys.contains("REQUIREMENTS↔PLAN 一致性映射表"));
        assertTrue(sys.contains("metricCodes ⇔ plan.metrics 的 bizName 集合，顺序一致"));
        assertTrue(sys.contains("answerFactTypes 含 CHANGE_VALUE 或 CHANGE_RATE ⇔ calculation.type=CHANGE"));
        assertTrue(sys.contains("organizations 非空时 dimensions 必须包含"));
        assertTrue(sys.contains("THRESHOLD 计数、全省 RANKING、省均值多机构比较必须包含"));
        assertTrue(sys.contains("rank/rank_from_bottom 只允许"));
        assertTrue(sys.contains("benchmark=COMPARE/PROVINCE_AVERAGE 对象必须原样同时存在于两个合同"));
        assertTrue(sys.contains("数量槽位映射见 PLAN 规则 15"));
    }

    @Test
    void toolResultRepairExplainsFeedbackFieldsBeforeStageGuidance() {
        BankPlanToolResult failed = BankPlanToolResult.failed(1, "trace-tool-result", null,
                BankPlanToolResult.Stage.COMPILE, "JDBC_GRAMMAR",
                Map.of("calculation", List.of("DIRECT", "CHANGE")),
                List.of("把较早日期移入 baselineStartDate/baselineEndDate，而不是扩大 startDate。"));
        String content = BankPlanPromptComposer.buildSinglePassToolRepairUserContent(
                "存款是多少？", "{\"requirements\":{},\"plan\":{}}", failed, null);

        assertTrue(content.contains("<tool_result>"));
        assertTrue(content.contains("</tool_result>"));
        assertTrue(content.contains("failedStage 标明失败阶段（VALIDATION/COMPILE/TRANSLATE/"));
        assertTrue(content.contains("allowedValues 列出该槽位的合法取值集合"));
        assertTrue(content.contains("JDBC_GRAMMAR"));

        String plainRepair = BankPlanPromptComposer.buildSinglePassRepairUserContent(
                "存款是多少？", "{\"requirements\":{},\"plan\":{}}", "boom", null);
        assertFalse(plainRepair.contains("<tool_result>"), "plain errors keep the <error> tag");
        assertFalse(plainRepair.contains("failedStage 标明失败阶段"),
                "field legend is exclusive to tool_result repairs");
    }

    @Test
    void catalogLeakSanitizerKeepsDiagnosisAndStripsOnlyTheCatalogTail() {
        String leaked = "intent 非法值 RATIO_X；可填写值目录（只能从下列内容中选择）：\n"
                + "- intent: [POINT_QUERY, AGGREGATION]\n- metrics: [ZB001]";
        assertEquals("intent 非法值 RATIO_X；",
                BankPlanPromptComposer.sanitizeCatalogLeak(leaked));
        assertEquals(
                "contract validation failed; output one complete JSON object for the current stage",
                BankPlanPromptComposer.sanitizeCatalogLeak("【语义目录】\n指标代码清单……"));
        assertEquals("plain failure", BankPlanPromptComposer.sanitizeCatalogLeak("plain failure"));
    }
}
