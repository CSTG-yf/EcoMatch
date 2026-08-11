package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankRequestContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFinalAnswerProcessorTest {

    @Test
    void acceptsOnlyTheAnswerTextFromAStrictFactReferencedJsonResponse() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"增长6.33%。\",\"factIds\":[\"F4\"]}");
        ExecuteContext context = changeContext();

        assertTrue(processor.accept(context));
        processor.process(context);

        assertEquals("增长6.33%。", context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals("SUCCEEDED", trace.get("status"));
        assertEquals(1, trace.get("attempts"));
    }

    @Test
    void returnsExactJsonContractFeedbackThenLetsTheModelRepairOnce() {
        List<String> answers = new ArrayList<>(List.of("增长6.33%",
                "{\"answer\":\"增长6.33%。\",\"factIds\":[\"F4\"]}"));
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return answers.remove(0);
        });
        ExecuteContext context = changeContext();

        processor.process(context);

        assertEquals("增长6.33%。", context.getResponse().getTextSummary());
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).contains("\"answer\":\"<直接回答>\""));
        assertTrue(prompts.get(0).contains("绝不可原样输出"));
        assertTrue(prompts.get(0).contains("最高或最低"));
        assertTrue(prompts.get(0).contains("<result_facts>"));
        assertTrue(prompts.get(1).contains("ANSWER_JSON_INVALID"));
    }

    @Test
    void rejectsANumberThatIsNotBackedByTheSelectedFactIds() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"增加1.37亿元。\",\"factIds\":[\"F4\"]}");
        ExecuteContext context = changeContext();

        processor.process(context);

        assertNull(context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals("FAILED", trace.get("status"));
        assertEquals(2, trace.get("attempts"));
        assertTrue(((List<?>) trace.get("errors")).toString().contains("ANSWER_UNGROUNDED_NUMBER"));
    }

    @Test
    void rejectsFactTypesThatTheModelDidNotDeclareAsNeeded() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"当前余额23.03亿元，增长6.33%。\",\"factIds\":[\"F1\",\"F4\"]}");
        ExecuteContext context = changeContext();

        processor.process(context);

        assertNull(context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals("FAILED", trace.get("status"));
        assertTrue(((List<?>) trace.get("errors")).toString()
                .contains("ANSWER_UNREQUESTED_FACT_TYPES"));
    }

    @Test
    void usesAResultDerivedTrendFactInsteadOfQuestionKeywordRules() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"整体呈上升趋势，2025-03为41.96亿元。\",\"factIds\":[\"F1\",\"F6\"]}");
        ExecuteContext context = trendContext();

        processor.process(context);

        assertEquals("整体呈上升趋势，2025-03为41.96亿元。",
                context.getResponse().getTextSummary());
    }

    @Test
    void omitsAnIndeterminateTrendWhenTheContractRequestsIt() {
        ExecuteContext context = trendContext();
        context.getResponse().setQueryResults(List.of(
                row("2025-03-31", "41.96"), row("2025-06-30", "42.32"),
                row("2025-09-30", "41.96")));
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"2025-03为41.96亿元，2025-06为42.32亿元，2025-09为41.96亿元。\","
                        + "\"factIds\":[\"F1\",\"F2\",\"F3\"]}");

        processor.process(context);

        assertEquals("2025-03为41.96亿元，2025-06为42.32亿元，2025-09为41.96亿元。",
                context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals("SUCCEEDED", trace.get("status"));
    }

    @Test
    void repairsDayPrecisionLabelsForQuarterlyAnswers() {
        List<String> answers = new ArrayList<>(List.of(
                "{\"answer\":\"2025年3月31日数值为41.96亿元，整体呈上升趋势。\",\"factIds\":[\"F1\",\"F6\"]}",
                "{\"answer\":\"2025-03数值为41.96亿元，整体呈上升趋势。\",\"factIds\":[\"F1\",\"F6\"]}"));
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return answers.remove(0);
        });
        ExecuteContext context = trendContext();

        processor.process(context);

        assertEquals("2025-03数值为41.96亿元，整体呈上升趋势。",
                context.getResponse().getTextSummary());
        assertTrue(prompts.get(1).contains("ANSWER_PERIOD_LABEL_MUST_USE_YYYY_MM"));
    }

    @Test
    void repairsQuarterEndLabelsToCanonicalMonthKeys() {
        List<String> answers = new ArrayList<>(List.of(
                "{\"answer\":\"2025年一季度末数值为41.96亿元，整体呈上升趋势。\",\"factIds\":[\"F1\",\"F6\"]}",
                "{\"answer\":\"2025-03数值为41.96亿元，整体呈上升趋势。\",\"factIds\":[\"F1\",\"F6\"]}"));
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return answers.remove(0);
        });
        ExecuteContext context = trendContext();

        processor.process(context);

        assertEquals("2025-03数值为41.96亿元，整体呈上升趋势。",
                context.getResponse().getTextSummary());
        assertTrue(prompts.get(1).contains("ANSWER_PERIOD_LABEL_MUST_USE_YYYY_MM"));
    }

    @Test
    void publishesCanonicalMetricNameAndUnitWithEachResultFact() {
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return "{\"answer\":\"净利润为239.28亿元。\",\"factIds\":[\"F1\"]}";
        });
        ExecuteContext context = namedMetricContext();

        processor.process(context);

        assertEquals("净利润为239.28亿元。", context.getResponse().getTextSummary());
        assertTrue(prompts.get(0).contains("metric_name"));
        assertTrue(prompts.get(0).contains("净利润"));
        assertTrue(prompts.get(0).contains("metric_unit"));
    }

    @Test
    void tellsTheModelToUseAbsoluteGapForHigherLowerMagnitude() {
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return "{\"answer\":\"各项存款余额54.65亿元，低于全省均值18.11亿元。\",\"factIds\":[\"F1\",\"F2\",\"F4\"]}";
        });
        ExecuteContext context = provinceAverageContext();

        processor.process(context);

        assertEquals("各项存款余额54.65亿元，低于全省均值18.11亿元。",
                context.getResponse().getTextSummary());
        assertTrue(prompts.get(0).contains("绝对差额"));
    }

    @Test
    void groundsRoundedMagnitudeFromSignedGapFactWhenAbsoluteFactWasNotSelected() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"各项存款余额54.65亿元，低于全省均值18.11亿元。\",\"factIds\":[\"F1\",\"F2\",\"F3\"]}");
        ExecuteContext context = provinceAverageContext();

        processor.process(context);

        assertEquals("各项存款余额54.65亿元，低于全省均值18.11亿元。",
                context.getResponse().getTextSummary());
    }

    @Test
    void rejectsTrendWordsWhenNoTrendFactWasSelected() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"整体呈上升趋势，净利润为239.28万元。\",\"factIds\":[\"F1\"]}");
        ExecuteContext context = namedMetricContext();

        processor.process(context);

        assertNull(context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertTrue(((List<?>) trace.get("errors")).toString().contains("ANSWER_UNREQUESTED_TREND"));
    }

    @Test
    void requiresValueAndRankFactsForEveryRequestedRankingMetric() {
        ExecuteContext context = namedMetricContext();
        context.getParseInfo().getProperties().put(BankRequestContract.PROPERTY_KEY,
                BankRequestContract.builder().version("1.0").action(BankRequestContract.Action.EXECUTE)
                        .intent(BankIntentType.RANKING).metricCodes(List.of("ZB011", "ZB001"))
                        .organizationCodes(List.of("ORG007"))
                        .time(BankRequestContractTime.changeTime())
                        .answerFactTypes(List.of(BankRequestContract.AnswerFactType.VALUE,
                                BankRequestContract.AnswerFactType.RANK))
                        .build());
        context.getResponse().setQueryColumns(List.of(column("metric_code"), column("metric_value"),
                column("rank_position")));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("metric_code", "ZB011");
        first.put("metric_value", new BigDecimal("239.28"));
        first.put("rank_position", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("metric_code", "ZB001");
        second.put("metric_value", new BigDecimal("110.62"));
        second.put("rank_position", 2);
        context.getResponse().setQueryResults(List.of(first, second));
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"净利润239.28万元（第2名）。\",\"factIds\":[\"F1\",\"F2\"]}");

        processor.process(context);

        assertNull(context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertTrue(((List<?>) trace.get("errors")).toString()
                .contains("ANSWER_REQUIRED_METRIC_VALUE_MISSING: ZB001"));
    }

    @Test
    void acceptsDailyAverageAsAValueFactFromThePublishedResultContract() {
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"期间日均值为12.34亿元。\",\"factIds\":[\"F1\"]}");
        ExecuteContext context = dailyAverageContext();

        processor.process(context);

        assertEquals("期间日均值为12.34亿元。", context.getResponse().getTextSummary());
    }

    @Test
    void requiresTheModelOwnedRequirementsContractBeforeFinalAnswerGeneration() {
        ExecuteContext context = changeContext();
        context.getParseInfo().getProperties().remove(BankRequestContract.PROPERTY_KEY);
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) ->
                "{\"answer\":\"增长6.33%。\",\"factIds\":[\"F4\"]}");

        assertFalse(processor.accept(context));
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals(List.of("FINAL_ANSWER_REQUIREMENTS_MISSING"), trace.get("errors"));
    }

    private ExecuteContext changeContext() {
        ExecuteContext context = new ExecuteContext(ChatExecuteReq.builder()
                .queryText("江苏省J市农商行的个人贷款余额从2024年末到2025-07-31，增幅是多少？").build());
        context.setAgent(agent());
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("intent", "CHANGE", "metrics", List.of(Map.of("bizName", "ZB006"))));
        parseInfo.getProperties().put(BankRequestContract.PROPERTY_KEY,
                BankRequestContract.builder().version("1.0").action(BankRequestContract.Action.EXECUTE)
                        .intent(BankIntentType.CHANGE).metricCodes(List.of("ZB006"))
                        .organizationCodes(List.of("ORG010"))
                        .time(BankRequestContractTime.changeTime())
                        .answerFactTypes(List.of(BankRequestContract.AnswerFactType.CHANGE_RATE))
                        .build());
        context.setParseInfo(parseInfo);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        result.setQueryColumns(List.of(column("current_value"), column("baseline_value"),
                column("absolute_change"), column("percent_change")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("current_value", new BigDecimal("23.03"));
        row.put("baseline_value", new BigDecimal("21.66"));
        row.put("absolute_change", new BigDecimal("1.37"));
        row.put("percent_change", new BigDecimal("6.325023084025859"));
        result.setQueryResults(List.of(row));
        context.setResponse(result);
        return context;
    }

    private ExecuteContext trendContext() {
        ExecuteContext context = new ExecuteContext(ChatExecuteReq.builder()
                .queryText("请分析各季度末数值的变化趋势。")
                .build());
        context.setAgent(agent());
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("intent", "TREND", "metrics", List.of(Map.of("bizName", "ZB001"))));
        parseInfo.getProperties().put(BankRequestContract.PROPERTY_KEY,
                BankRequestContract.builder().version("1.0").action(BankRequestContract.Action.EXECUTE)
                        .intent(BankIntentType.TREND).metricCodes(List.of("ZB001"))
                        .organizationCodes(List.of("ORG001"))
                        .time(BankRequestContractTime.trendTime())
                        .answerFactTypes(List.of(BankRequestContract.AnswerFactType.VALUE,
                                BankRequestContract.AnswerFactType.TREND_DIRECTION))
                        .build());
        context.setParseInfo(parseInfo);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        result.setQueryColumns(List.of(column("data_date"), column("metric_value")));
        result.setQueryResults(List.of(row("2025-03-31", "41.96"), row("2025-06-30", "42.32"),
                row("2025-09-30", "43.11"), row("2025-12-31", "44.20"),
                row("2026-03-31", "45.01")));
        context.setResponse(result);
        return context;
    }

    private ExecuteContext dailyAverageContext() {
        ExecuteContext context = new ExecuteContext(ChatExecuteReq.builder()
                .queryText("江苏省A市农商行在2025年第二季度的日均贷款余额是多少？").build());
        context.setAgent(agent());
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("intent", "AGGREGATION", "metrics", List.of(Map.of("bizName", "ZB002"))));
        parseInfo.getProperties().put(BankRequestContract.PROPERTY_KEY,
                BankRequestContract.builder().version("1.0").action(BankRequestContract.Action.EXECUTE)
                        .intent(BankIntentType.AGGREGATION).metricCodes(List.of("ZB002"))
                        .organizationCodes(List.of("ORG001"))
                        .time(BankRequestContractTime.trendTime())
                        .answerFactTypes(List.of(BankRequestContract.AnswerFactType.VALUE))
                        .build());
        context.setParseInfo(parseInfo);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        result.setQueryColumns(List.of(column("daily_average")));
        result.setQueryResults(List.of(Map.of("daily_average", new BigDecimal("12.34"))));
        context.setResponse(result);
        return context;
    }

    private ExecuteContext namedMetricContext() {
        ExecuteContext context = new ExecuteContext(ChatExecuteReq.builder()
                .queryText("江苏省G市农商行的净利润是多少？").build());
        context.setAgent(agent());
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("intent", "POINT_QUERY", "metrics", List.of(Map.of("bizName", "ZB011"))));
        parseInfo.getProperties().put(BankRequestContract.PROPERTY_KEY,
                BankRequestContract.builder().version("1.0").action(BankRequestContract.Action.EXECUTE)
                        .intent(BankIntentType.POINT_QUERY).metricCodes(List.of("ZB011"))
                        .organizationCodes(List.of("ORG007"))
                        .time(BankRequestContractTime.changeTime())
                        .answerFactTypes(List.of(BankRequestContract.AnswerFactType.VALUE))
                        .build());
        context.setParseInfo(parseInfo);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        result.setQueryColumns(List.of(column("metric_code"), column("metric_value")));
        result.setQueryResults(List.of(Map.of("metric_code", "ZB011",
                "metric_value", new BigDecimal("239.28"))));
        context.setResponse(result);
        return context;
    }

    private ExecuteContext provinceAverageContext() {
        ExecuteContext context = namedMetricContext();
        context.getParseInfo().getProperties().put(BankRequestContract.PROPERTY_KEY,
                BankRequestContract.builder().version("1.0").action(BankRequestContract.Action.EXECUTE)
                        .intent(BankIntentType.COMPARISON).metricCodes(List.of("ZB001"))
                        .organizationCodes(List.of("ORG004"))
                        .time(BankRequestContractTime.changeTime())
                        .answerFactTypes(List.of(BankRequestContract.AnswerFactType.VALUE,
                                BankRequestContract.AnswerFactType.PROVINCE_AVERAGE,
                                BankRequestContract.AnswerFactType.GAP_VALUE))
                        .build());
        context.getResponse().setQueryColumns(List.of(column("metric_code"), column("metric_value"),
                column("provincial_average"), column("gap_value"), column("absolute_gap")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric_code", "ZB001");
        row.put("metric_value", new BigDecimal("54.65"));
        row.put("provincial_average", new BigDecimal("72.758"));
        row.put("gap_value", new BigDecimal("-18.108"));
        row.put("absolute_gap", new BigDecimal("18.108"));
        context.getResponse().setQueryResults(List.of(row));
        return context;
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setChatAppConfig(Map.of(BankFinalAnswerProcessor.APP_KEY, ChatApp.builder()
                .enable(true).prompt(BankFinalAnswerProcessor.INSTRUCTION).build()));
        return agent;
    }

    private static Map<String, Object> row(String date, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("data_date", date);
        row.put("metric_value", new BigDecimal(value));
        return row;
    }

    private static QueryColumn column(String name) {
        return new QueryColumn(name, "NUMBER", name);
    }

    private static final class BankRequestContractTime {
        private BankRequestContractTime() {}

        static com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeRange changeTime() {
            return com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeRange.builder()
                    .startDate(LocalDate.of(2025, 7, 31)).endDate(LocalDate.of(2025, 7, 31))
                    .baselineStartDate(LocalDate.of(2024, 12, 31))
                    .baselineEndDate(LocalDate.of(2024, 12, 31))
                    .granularity(com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeGranularity.DAY)
                    .comparison(com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeComparison.START_OF_YEAR)
                    .build();
        }

        static com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeRange trendTime() {
            return com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeRange.builder()
                    .startDate(LocalDate.of(2025, 3, 31)).endDate(LocalDate.of(2026, 3, 31))
                    .granularity(com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeGranularity.QUARTER)
                    .comparison(com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan.TimeComparison.NONE)
                    .build();
        }
    }
}
