package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.JsonUtil;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic-contract coverage for the fail-closed bank result invariant audit. */
class BankResultInvariantHandlerTest {

    private static final List<String> RANK_COLUMNS = List.of("org_code", "org_name",
            "metric_code", "metric_value", "rank_position");

    @Test
    void rejectsRankCardinalityBeyondTheContractSlice() {
        QueryResult result = rankResult(rankContract(3, null), rankRows(1, 2, 3, 4, 5));

        boolean applied = new BankResultInvariantHandler().apply(result);

        assertFalse(applied);
        BankPlanToolResult toolResult = toolResult(result);
        assertEquals(BankPlanToolResult.Status.FAILED, toolResult.getStatus());
        assertEquals(BankPlanToolResult.Stage.RESULT_SEMANTIC, toolResult.getFailedStage());
        assertTrue(toolResult.getErrorCode().startsWith("INVARIANT_VIOLATION"),
                "errorCode must start with the invariant prefix: " + toolResult.getErrorCode());
        assertEquals("INVARIANT_VIOLATION_RANK", toolResult.getErrorCode());
        assertTrue(toolResult.getCorrectionHints().get(0).contains("超出"));
        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
        assertTrue(result.getQueryResults().isEmpty(), "violated rows must be withheld");
        assertTrue(result.getQueryColumns().isEmpty());
    }

    @Test
    void rejectsTopRankSliceThatDoesNotStartAtOne() {
        QueryResult result = rankResult(rankContract(3, null), rankRows(2, 3));

        boolean applied = new BankResultInvariantHandler().apply(result);

        assertFalse(applied);
        assertEquals("INVARIANT_VIOLATION_RANK", toolResult(result).getErrorCode());
        assertTrue(toolResult(result).getCorrectionHints().get(0).contains("连续"));
    }

    @Test
    void passesConsecutiveTopRankSliceInsideTheContract() {
        QueryResult result = rankResult(rankContract(3, null), rankRows(1, 2, 3));

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertEquals(3, result.getQueryResults().size());
    }

    @Test
    void passesTiedTopSliceWhereContinuityIsUndecidable() {
        QueryResult result = rankResult(rankContract(3, null), rankRows(1, 1, 3));

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
    }

    @Test
    void passesBottomOnlySliceWithoutFalsePositiveOnHighRanks() {
        QueryResult result = rankResult(rankContract(null, 3), rankRows(8, 9, 10));

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
    }

    @Test
    void rejectsPhantomOrganizationOutsideTheContractSelection() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization")
                .selectedOrganizationCodes(List.of("ORG001", "ORG002"))
                .metrics(List.of(metric("zb010", "ZB010"))).build();
        QueryResult result = result(contract,
                List.of("org_code", "org_name", "metric_code", "metric_value"),
                List.of(row("org_code", "ORG001", "org_name", "机构一", "metric_code", "ZB010",
                                "metric_value", new BigDecimal("12.5")),
                        row("org_code", "ORG009", "org_name", "机构九", "metric_code", "ZB010",
                                "metric_value", new BigDecimal("9.5"))));

        assertFalse(new BankResultInvariantHandler().apply(result));
        assertEquals("INVARIANT_VIOLATION_ORG", toolResult(result).getErrorCode());
        assertTrue(toolResult(result).getCorrectionHints().get(0).contains("ORG009"));
        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
    }

    @Test
    void passesWhenEveryOrganizationIsInsideTheContractSelection() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization")
                .selectedOrganizationCodes(List.of("ORG001", "ORG002"))
                .metrics(List.of(metric("zb010", "ZB010"))).build();
        QueryResult result = result(contract,
                List.of("org_code", "org_name", "metric_code", "metric_value"),
                List.of(row("org_code", "ORG001", "org_name", "机构一", "metric_code", "ZB010",
                                "metric_value", new BigDecimal("12.5")),
                        row("org_code", "ORG002", "org_name", "机构二", "metric_code", "ZB010",
                                "metric_value", new BigDecimal("9.5"))));

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
    }

    @Test
    void rejectsDateOutsideTheContractDateRange() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.TREND)
                .timeColumn("data_date")
                .selectedDates(List.of("2026-01-02", "2026-01-03"))
                .metrics(List.of(metric("zb010", "ZB010"))).build();
        QueryResult result = result(contract,
                List.of("data_date", "metric_value", "quarter_change"),
                List.of(row("data_date", "2026-01-02", "metric_value", new BigDecimal("10"),
                                "quarter_change", null),
                        row("data_date", "2026-01-04", "metric_value", new BigDecimal("11"),
                                "quarter_change", new BigDecimal("1"))));

        assertFalse(new BankResultInvariantHandler().apply(result));
        assertEquals("INVARIANT_VIOLATION_DATE", toolResult(result).getErrorCode());
        assertTrue(toolResult(result).getCorrectionHints().get(0).contains("2026-01-04"));
        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
    }

    @Test
    void passesWhenAllDatesAreInsideTheContractRange() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.TREND)
                .timeColumn("data_date")
                .selectedDates(List.of("2026-01-02", "2026-01-03"))
                .metrics(List.of(metric("zb010", "ZB010"))).build();
        QueryResult result = result(contract,
                List.of("data_date", "metric_value", "quarter_change"),
                List.of(row("data_date", "2026-01-02", "metric_value", new BigDecimal("10"),
                                "quarter_change", null),
                        row("data_date", "2026-01-03", "metric_value", new BigDecimal("11"),
                                "quarter_change", new BigDecimal("1"))));

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
    }

    @Test
    void rejectsFreeContractMissingADeclaredColumn() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.FREE)
                .metrics(List.of(metric("zb007", "ZB007"), metric("zb008", "ZB008"))).build();
        QueryResult result = result(contract, List.of("zb007"),
                List.of(row("zb007", new BigDecimal("10"))));

        assertFalse(new BankResultInvariantHandler().apply(result));
        assertEquals("INVARIANT_VIOLATION_FREE_COLUMNS", toolResult(result).getErrorCode());
        assertTrue(toolResult(result).getCorrectionHints().get(0).contains("zb008"));
        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
    }

    @Test
    void passesFreeContractWithAllDeclaredColumnsPresent() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.FREE)
                .metrics(List.of(metric("zb007", "ZB007"), metric("zb008", "ZB008"))).build();
        QueryResult result = result(contract, List.of("ZB008", "ZB007"),
                List.of(row("zb007", new BigDecimal("10"), "zb008", new BigDecimal("20"))));

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
    }

    @Test
    void skipsEveryAssertionWhenTheContractFieldsAreMissing() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY)
                .organizationColumn("bank_organization")
                .metrics(List.of(metric("zb010", "ZB010"))).build();
        List<Map<String, Object>> rows = List.of(
                row("org_code", "ORG077", "org_name", "机构七七", "metric_code", "ZB010",
                        "aggregate_value", new BigDecimal("3.3"), "min_value", new BigDecimal("3.3"),
                        "max_value", new BigDecimal("3.3"), "observation_count", 1));
        QueryResult result = result(contract, List.of("org_code", "org_name", "metric_code",
                "aggregate_value", "min_value", "max_value", "observation_count"), rows);

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertEquals(rows, result.getQueryResults());
    }

    @Test
    void passesEmptyResultWithoutAnyInvariantJudgement() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                .organizationColumn("bank_organization")
                .selectedOrganizationCodes(List.of("ORG001"))
                .selectedDates(List.of("2026-01-02"))
                .topRankLimit(3)
                .metrics(List.of(metric("zb010", "ZB010"))).build();
        QueryResult result = result(contract, RANK_COLUMNS, List.of());

        assertTrue(new BankResultInvariantHandler().apply(result));
        assertEquals(BankPlanToolResult.Status.SUCCEEDED, toolResult(result).getStatus());
        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertTrue(result.getQueryResults().isEmpty());
    }

    @Test
    void processMarksTheChainResponseFailedForAnInvariantViolation() {
        QueryResult result = rankResult(rankContract(3, null), rankRows(1, 2, 3, 4, 5));
        ExecuteContext context = new ExecuteContext(ChatExecuteReq.builder().queryId(20L)
                .chatId(10).agentId(7).parseId(1).queryText("synthetic question")
                .user(User.get(2L, "analyst")).build());
        context.setResponse(result);

        BankResultInvariantHandler handler = new BankResultInvariantHandler();
        assertTrue(handler.accept(context));
        handler.process(context);

        assertEquals(QueryState.SEARCH_EXCEPTION, context.getResponse().getQueryState());
        assertEquals("INVARIANT_VIOLATION_RANK", toolResult(result).getErrorCode());
    }

    private static BankResultProjector.MetricBinding metric(String semanticColumn,
            String metricCode) {
        return BankResultProjector.MetricBinding.builder().semanticColumn(semanticColumn)
                .metricCode(metricCode).build();
    }

    private static BankResultProjector.Contract rankContract(Integer topRankLimit,
            Integer bottomRankLimit) {
        return BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                .organizationColumn("bank_organization")
                .topRankLimit(topRankLimit)
                .bottomRankLimit(bottomRankLimit)
                .metrics(List.of(metric("zb010", "ZB010"))).build();
    }

    private static List<Map<String, Object>> rankRows(int... ranks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < ranks.length; index++) {
            rows.add(row("org_code", "ORG0" + (index + 1), "org_name", "机构" + (index + 1),
                    "metric_code", "ZB010", "metric_value",
                    new BigDecimal(50 - ranks[index]), "rank_position", ranks[index]));
        }
        return rows;
    }

    private static QueryResult rankResult(BankResultProjector.Contract contract,
            List<Map<String, Object>> rows) {
        return result(contract, RANK_COLUMNS, rows);
    }

    private static QueryResult result(BankResultProjector.Contract contract,
            List<String> columns, List<Map<String, Object>> rows) {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankResultProjector.CONTRACT_PROPERTY,
                JsonUtil.objectToMap(contract));
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY,
                completedToolResult(columns, rows));
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        result.setQueryColumns(columns.stream()
                .map(column -> new QueryColumn(column, "STRING", column)).toList());
        result.setQueryResults(rows);
        return result;
    }

    /** Mirrors the state left behind by a successful BankResultProjectionHandler pass. */
    private static BankPlanToolResult completedToolResult(List<String> columns,
            List<Map<String, Object>> rows) {
        return BankPlanToolResult.started(1, "trace-invariant", "fingerprint-1", "STRUCT", columns)
                .succeed(BankPlanToolResult.Stage.SQL_SAFETY)
                .succeed(BankPlanToolResult.Stage.DATABASE_PREPARE)
                .succeed(BankPlanToolResult.Stage.DATABASE_EXECUTE)
                .complete(columns, rows);
    }

    private static BankPlanToolResult toolResult(QueryResult result) {
        return (BankPlanToolResult) result.getChatContext().getProperties()
                .get(BankPlanToolResult.PROPERTY_KEY);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }
}
