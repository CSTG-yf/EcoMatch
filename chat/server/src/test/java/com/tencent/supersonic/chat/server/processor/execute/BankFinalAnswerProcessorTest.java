package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankResultProjector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFinalAnswerProcessorTest {

    @Test
    void retriesGenericInsightAndKeepsOnlyTheDirectGroundedAnswer() {
        List<String> answers = new ArrayList<>(
                List.of("查询返回1条记录。current_value范围为23.03至23.03；percent_change范围为6.325023至6.325023。",
                        "增长6.33%"));
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return answers.remove(0);
        });
        ExecuteContext context = changeContext();

        assertTrue(processor.accept(context));
        processor.process(context);

        assertEquals("增长6.33%", context.getResponse().getTextSummary());
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("validation_feedback"));
        assertTrue(prompts.get(1).contains("不要输出查询记录数、范围或首末记录"));
        assertTrue(prompts.get(0).contains("ZB011=净利润"));
        assertTrue(prompts.get(0).contains("ZB013=不良贷款率"));
    }

    @Test
    void rejectsNumbersThatCannotBeGroundedInQuestionOrExecutedRows() {
        BankFinalAnswerProcessor processor =
                new BankFinalAnswerProcessor((app, prompt) -> "增长999.99%");
        ExecuteContext context = changeContext();

        processor.process(context);

        assertNull(context.getResponse().getTextSummary());
        Map<?, ?> trace = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals("FAILED", trace.get("status"));
        assertEquals(3, trace.get("attempts"));
    }

    @Test
    void retriesAChangeAmountAnswerThatLeaksUnaskedSupportingValues() {
        List<String> answers =
                new ArrayList<>(List.of("当前值23.03亿元，基期21.66亿元，增加1.37亿元，增幅6.33%。", "增加1.37亿元。"));
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return answers.remove(0);
        });
        ExecuteContext context = changeContext();
        context.setRequest(ChatExecuteReq.builder()
                .queryText("江苏省J市农商行的个人贷款余额从2024年末到2025-07-31，变化了多少？").build());

        processor.process(context);

        assertEquals("增加1.37亿元。", context.getResponse().getTextSummary());
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("变化额问题包含未询问的支撑数值"));
    }

    @Test
    void retriesAQuarterlyAnswerThatOmitsTheOverallTrend() {
        List<String> answers =
                new ArrayList<>(List.of(
                        "2025-03为41.96亿元，2025-06为41.78亿元，2025-09为41.35亿元，"
                                + "2025-12为42.25亿元，2026-03最高，为42.32亿元。",
                        "整体呈上升趋势，2026-03数值最高，为42.32亿元。"));
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return answers.remove(0);
        });
        ExecuteContext context = quarterlyTrendContext();

        processor.process(context);

        assertEquals("整体呈上升趋势，2026-03数值最高，为42.32亿元。", context.getResponse().getTextSummary());
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("整体上升趋势"));
    }

    @Test
    void appendsTheMetricCatalogToAnExistingAgentPromptThatPredatesTheCatalog() {
        List<String> prompts = new ArrayList<>();
        BankFinalAnswerProcessor processor = new BankFinalAnswerProcessor((app, prompt) -> {
            prompts.add(prompt.text());
            return "增长6.33%";
        });
        ExecuteContext context = changeContext();
        context.getAgent().getChatAppConfig().get(BankFinalAnswerProcessor.APP_KEY)
                .setPrompt("#Question\n{{question}}\n#Result\n{{data}}\n#Direct answer");

        processor.process(context);

        assertEquals("增长6.33%", context.getResponse().getTextSummary());
        assertTrue(prompts.get(0).contains("ZB011=净利润"));
        assertTrue(prompts.get(0).contains("ZB013=不良贷款率"));
        assertTrue(prompts.get(0).indexOf("# Metric catalog") < prompts.get(0)
                .indexOf("#Direct answer"));
    }

    @Test
    void acceptsPersistedBankPlansWithoutAResultContractButRejectsGeneralQueries() {
        ExecuteContext context = changeContext();
        context.getAgent().getChatAppConfig().get(BankFinalAnswerProcessor.APP_KEY)
                .setEnable(false);
        BankFinalAnswerProcessor processor =
                new BankFinalAnswerProcessor((app, prompt) -> "增长6.33%");

        assertTrue(!processor.accept(context));
        Map<?, ?> skipped = (Map<?, ?>) context.getParseInfo().getProperties()
                .get(BankFinalAnswerProcessor.TRACE_PROPERTY);
        assertEquals("SKIPPED", skipped.get("status"));
        assertEquals(List.of("FINAL_ANSWER_APP_DISABLED_OR_UNCONFIGURED"), skipped.get("errors"));

        context.getAgent().getChatAppConfig().get(BankFinalAnswerProcessor.APP_KEY).setEnable(true);
        context.getParseInfo().getProperties().remove(BankResultProjector.CONTRACT_PROPERTY);
        assertTrue(processor.accept(context));

        context.getParseInfo().getProperties().remove(BankPlanToolResult.PLAN_PROPERTY_KEY);
        assertTrue(!processor.accept(context));
    }

    private ExecuteContext changeContext() {
        ChatExecuteReq request = ChatExecuteReq.builder()
                .queryText("江苏省J市农商行的个人贷款余额从2024年末到2025-07-31，增幅是多少？").build();
        ExecuteContext context = new ExecuteContext(request);

        Agent agent = new Agent();
        agent.setChatAppConfig(Map.of(BankFinalAnswerProcessor.APP_KEY, ChatApp.builder()
                .enable(true).prompt(BankFinalAnswerProcessor.INSTRUCTION).build()));
        context.setAgent(agent);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankResultProjector.CONTRACT_PROPERTY,
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.MOM_YOY_CHANGE).build());
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("intent", "CHANGE", "metrics", List.of(Map.of("bizName", "ZB006"))));
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
        result.setTextResult("current_value:23.03, baseline_value:21.66, absolute_change:1.37, "
                + "percent_change:6.325023084025859");
        context.setResponse(result);
        return context;
    }

    private ExecuteContext quarterlyTrendContext() {
        ChatExecuteReq request = ChatExecuteReq.builder()
                .queryText("请分析江苏省A市农商行的各项存款余额从2025年一季度末到2026年一季度末的逐季变化，" + "各季度末数值是多少？哪个季度数值最高？")
                .build();
        ExecuteContext context = new ExecuteContext(request);

        Agent agent = new Agent();
        agent.setChatAppConfig(Map.of(BankFinalAnswerProcessor.APP_KEY, ChatApp.builder()
                .enable(true).prompt(BankFinalAnswerProcessor.INSTRUCTION).build()));
        context.setAgent(agent);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("intent", "CHANGE", "metrics", List.of(Map.of("bizName", "ZB001"))));
        context.setParseInfo(parseInfo);

        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        result.setQueryColumns(
                List.of(column("data_date"), column("metric_value"), column("quarter_change")));
        result.setQueryResults(List.of(row("2025-03-31", "41.96", null),
                row("2025-06-30", "41.78", "-0.18"), row("2025-09-30", "41.35", "-0.43"),
                row("2025-12-31", "42.25", "0.90"), row("2026-03-31", "42.32", "0.07")));
        context.setResponse(result);
        return context;
    }

    private Map<String, Object> row(String date, String value, String change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("data_date", date);
        row.put("metric_value", new BigDecimal(value));
        row.put("quarter_change", change == null ? null : new BigDecimal(change));
        return row;
    }

    private QueryColumn column(String name) {
        return new QueryColumn(name, "NUMBER", name);
    }
}
