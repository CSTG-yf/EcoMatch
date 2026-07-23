package com.tencent.supersonic.chat.server.processor.execute;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessInsightProcessorTest {

    @Test
    void recommendsTrendChartAndBuildsEvidenceFromActualValues() {
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(List.of(column("month", "DATE"), column("balance", "NUMBER")));
        result.setQueryResults(
                List.of(row("2026-01", 100), row("2026-02", 120), row("2026-03", 150)));
        ExecuteContext context = new ExecuteContext(new ChatExecuteReq());
        context.setResponse(result);

        BusinessInsightProcessor processor = new BusinessInsightProcessor();
        processor.process(context);

        assertEquals("LINE", result.getRecommendedChart().getChartType());
        assertEquals("2026-01至2026-03", result.getBusinessExplanation().getTimeRange());
        assertTrue(result.getBusinessExplanation().getEvidence().contains("balance范围为100至150"));
        assertTrue(result.getTextSummary().contains("balance首末记录变化50%"));
    }

    @Test
    void warnsInsteadOfClaimingTrendForSmallSamples() {
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(List.of(column("branch", "CATEGORY"), column("amount", "NUMBER")));
        result.setQueryResults(List.of(row("A", 10), row("B", 20)));
        ExecuteContext context = new ExecuteContext(new ChatExecuteReq());
        context.setResponse(result);

        new BusinessInsightProcessor().process(context);

        assertEquals("BAR", result.getRecommendedChart().getChartType());
        assertTrue(result.getBusinessExplanation().getWarnings().stream()
                .anyMatch(warning -> warning.contains("少于3条")));
    }

    @Test
    void coversDatasetChartProfiles() {
        assertEquals("KPI_CARD", recommend(List.of(column("metric_value", "NUMBER")),
                List.of(Map.of("metric_value", 10))));
        assertEquals("PIE",
                recommend(
                        List.of(column("metric_name", "CATEGORY"),
                                column("metric_value", "NUMBER")),
                        List.of(Map.of("metric_name", "存款", "metric_value", 10),
                                Map.of("metric_name", "贷款", "metric_value", 20))));
        assertEquals("COMBO",
                recommend(
                        List.of(column("data_date", "DATE"), column("zb009", "NUMBER"),
                                column("zb010", "NUMBER")),
                        List.of(Map.of("data_date", "2026-01", "zb009", 10, "zb010", 20),
                                Map.of("data_date", "2026-02", "zb009", 11, "zb010", 18),
                                Map.of("data_date", "2026-03", "zb009", 12, "zb010", 16))));
        assertEquals("TABLE",
                recommend(List.of(column("data_date", "DATE"), column("organization", "CATEGORY"),
                        column("metric_code", "CATEGORY"), column("metric_name", "CATEGORY"),
                        column("metric_unit", "CATEGORY"), column("metric_value", "NUMBER")),
                        List.of(detailRow("A", 10), detailRow("B", 20), detailRow("C", 30))));
    }

    @Test
    void derivesContributionAndRiskWarningFromResultData() {
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(
                List.of(column("branch", "CATEGORY"), column("overdue_rate", "NUMBER")));
        result.setQueryResults(List.of(riskRow("A", 10), riskRow("B", 30), riskRow("C", 60)));
        ExecuteContext context = new ExecuteContext(new ChatExecuteReq());
        context.setResponse(result);

        new BusinessInsightProcessor().process(context);

        assertTrue(
                result.getBusinessExplanation().getEvidence().contains("C的overdue_rate贡献度最高，为60%"));
        assertTrue(result.getBusinessExplanation().getWarnings().stream()
                .anyMatch(warning -> warning.contains("不替代监管报送")));
    }

    @Test
    void reachesRequiredAccuracyOnFrozenDataset() throws Exception {
        Path dataset = locateDataset();
        int total = 0;
        int matched = 0;
        for (String line : Files.readAllLines(dataset, StandardCharsets.UTF_8)) {
            JSONObject sample = JSON.parseObject(line);
            JSONObject resultJson = sample.getJSONObject("result");
            JSONArray columnNames = resultJson.getJSONArray("columns");
            List<QueryColumn> columns = columnNames.stream().map(String::valueOf)
                    .map(name -> column(name, inferShowType(name))).toList();
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Object rawRow : resultJson.getJSONArray("rows")) {
                JSONArray values = (JSONArray) rawRow;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    row.put(columnNames.getString(i), values.get(i));
                }
                rows.add(row);
            }
            String expected = sample.getJSONObject("chartAnnotation").getString("recommended");
            if (expected.equals(recommend(columns, rows))) {
                matched++;
            }
            total++;
        }
        assertTrue(total > 0);
        assertTrue((double) matched / total >= 0.90,
                String.format("chart accuracy was %d/%d", matched, total));
    }

    @Test
    void reachesRequiredExplanationCoverageOnFrozenDataset() throws Exception {
        int total = 0;
        int matched = 0;
        List<String> failures = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(locateDataset(), StandardCharsets.UTF_8)) {
            JSONObject sample = JSON.parseObject(line);
            JSONObject resultJson = sample.getJSONObject("result");
            JSONArray columnNames = resultJson.getJSONArray("columns");
            List<QueryColumn> columns = columnNames.stream().map(String::valueOf)
                    .map(name -> column(name, inferShowType(name))).toList();
            List<Map<String, Object>> rows = rows(resultJson, columnNames);
            JSONObject annotation = sample.getJSONObject("explanationAnnotation");
            ExecuteContext context =
                    context(sample.getString("question"), columns, rows, annotation, columnNames);

            new BusinessInsightProcessor().process(context);
            String summary = context.getResponse().getBusinessExplanation().getSummary();
            boolean claimsPresent =
                    annotation.getJSONArray("requiredClaims").stream().map(JSONObject.class::cast)
                            .allMatch(claim -> summary.contains(claim.getString("token")));
            JSONObject timeRange = annotation.getJSONObject("timeRange");
            boolean timePresent = summary.contains(timeRange.getString("start"))
                    && summary.contains(timeRange.getString("end"));
            boolean scopePresent = summary.contains(sample.getString("question"))
                    && summary.contains("不得外推到未展示机构或时间");
            boolean riskNoticePresent = annotation.getJSONArray("requiredRiskNotices").stream()
                    .map(String::valueOf).filter(notice -> notice.contains("不替代监管报送"))
                    .allMatch(notice -> summary.contains("不替代监管报送"));
            if (claimsPresent && timePresent && scopePresent && riskNoticePresent) {
                matched++;
            } else {
                failures.add(String.format("%s[claims=%s,time=%s,scope=%s,risk=%s]",
                        sample.getString("id"), claimsPresent, timePresent, scopePresent,
                        riskNoticePresent));
            }
            total++;
        }
        assertTrue(total > 0);
        assertTrue((double) matched / total >= 0.90,
                String.format("explanation coverage was %d/%d: %s", matched, total, failures));
    }

    private ExecuteContext context(String question, List<QueryColumn> columns,
            List<Map<String, Object>> rows, JSONObject annotation, JSONArray columnNames) {
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(columns);
        result.setQueryResults(rows);
        ExecuteContext context =
                new ExecuteContext(ChatExecuteReq.builder().queryText(question).build());
        context.setResponse(result);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        List<String> metricFields = columnNames.stream().map(String::valueOf)
                .filter(name -> "NUMBER".equals(inferShowType(name))).toList();
        JSONArray definitions = annotation.getJSONArray("metricDefinitions");
        for (int i = 0; i < definitions.size(); i++) {
            JSONObject definition = definitions.getJSONObject(i);
            String field = metricFields.stream()
                    .filter(name -> name.equalsIgnoreCase(definition.getString("code"))).findFirst()
                    .orElse(metricFields.get(Math.min(i, metricFields.size() - 1)));
            SchemaElement metric = SchemaElement.builder().name(definition.getString("name"))
                    .bizName(field).description(definition.getString("definition"))
                    .extInfo(Map.of("unit", definition.getString("unit"))).build();
            parseInfo.getMetrics().add(metric);
        }
        context.setParseInfo(parseInfo);
        return context;
    }

    private List<Map<String, Object>> rows(JSONObject resultJson, JSONArray columnNames) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Object rawRow : resultJson.getJSONArray("rows")) {
            JSONArray values = (JSONArray) rawRow;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columnNames.size(); i++) {
                row.put(columnNames.getString(i), values.get(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private String recommend(List<QueryColumn> columns, List<Map<String, Object>> rows) {
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(columns);
        result.setQueryResults(rows);
        ExecuteContext context = new ExecuteContext(new ChatExecuteReq());
        context.setResponse(result);
        new BusinessInsightProcessor().process(context);
        return result.getRecommendedChart().getChartType();
    }

    private Map<String, Object> detailRow(String organization, Number value) {
        return Map.of("data_date", "2026-01", "organization", organization, "metric_code", "M1",
                "metric_name", "余额", "metric_unit", "亿元", "metric_value", value);
    }

    private Map<String, Object> riskRow(String branch, Number value) {
        return Map.of("branch", branch, "overdue_rate", value);
    }

    private String inferShowType(String field) {
        String normalized = field.toLowerCase();
        if (normalized.matches(".*(date|time|day|month|year).*")) {
            return "DATE";
        }
        if (normalized.equals("metric_value") || normalized.matches("zb\\d+")) {
            return "NUMBER";
        }
        return "CATEGORY";
    }

    private Path locateDataset() {
        return Stream
                .of(Path.of("evaluation/bank_chart_explanation/test.jsonl"),
                        Path.of("../../evaluation/bank_chart_explanation/test.jsonl"),
                        Path.of("../../../evaluation/bank_chart_explanation/test.jsonl"))
                .filter(Files::exists).findFirst()
                .orElseThrow(() -> new IllegalStateException("DATA-03 test dataset not found"));
    }

    private QueryColumn column(String name, String showType) {
        QueryColumn column = new QueryColumn(name, "VARCHAR", name);
        column.setShowType(showType);
        return column;
    }

    private Map<String, Object> row(Object dimension, Object metric) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (dimension instanceof String && ((String) dimension).startsWith("2026-")) {
            row.put("month", dimension);
            row.put("balance", metric);
        } else {
            row.put("branch", dimension);
            row.put("amount", metric);
        }
        return row;
    }
}
