package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.response.MultiTurnContextResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Order;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.enums.QueryType;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiTurnContextEngineTest {

    private final MultiTurnContextEngine engine = new MultiTurnContextEngine();

    @Test
    public void shouldKeepLatestTenSuccessfulTurnsInChronologicalOrder() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        List<QueryResp> history = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            history.add(
                    successfulQuery(id, now.minus(Duration.ofMinutes(12 - id)), "第" + id + "轮查询"));
        }

        MultiTurnContextResp context = engine.build(history, 100L, "继续看贷款余额", now);

        assertEquals(10, context.getUsedRounds());
        assertEquals(Long.valueOf(3), context.getSourceQueryIds().get(0));
        assertEquals(Long.valueOf(12), context.getSourceQueryIds().get(9));
        assertEquals("loan_balance", context.getTurns().get(0).getMetrics().get(0));
        assertEquals("bank_organization", context.getTurns().get(0).getDimensions().get(0));
        assertTrue(context.getTurns().get(0).getFilters().get(0).contains("ORG003"));
        assertEquals("AGGREGATE", context.getTurns().get(0).getGranularity());
        assertEquals("SUCCESS", context.getTurns().get(0).getState());
        assertTrue(context.getTurns().get(0).getResultTable().contains("42.5"));
        assertTrue(context.getTurns().get(0).getTextSummary().contains("第3轮总结"));
        assertEquals("APPEND", context.getOperation());
    }

    @Test
    public void shouldRecognizeContextOperationsAndResetHistory() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        List<QueryResp> history = List.of(successfulQuery(1, now, "查询贷款余额"));

        assertEquals("REPLACE", engine.detectOperation("把机构换成南京分行"));
        assertEquals("REMOVE", engine.detectOperation("去掉小微企业条件"));
        assertEquals("DRILL_DOWN", engine.detectOperation("继续下钻到客户经理"));
        MultiTurnContextResp reset = engine.build(history, 100L, "清空上下文，重新开始", now);
        assertEquals("RESET", reset.getOperation());
        assertTrue(reset.getTurns().isEmpty());
    }

    @Test
    public void shouldExpireStaleHistory() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp stale = successfulQuery(1, now.minus(Duration.ofMinutes(31)), "旧查询");

        MultiTurnContextResp context = engine.build(List.of(stale), 100L, "继续查询", now);

        assertTrue(context.isExpired());
        assertEquals(0, context.getUsedRounds());
    }

    @Test
    public void shouldDiscardOldestTurnsWhenSummaryIsTooLong() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        String longQuestion = "贷款余额".repeat(700);
        List<QueryResp> history = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            history.add(
                    successfulQuery(id, now.minus(Duration.ofMinutes(10 - id)), id + longQuestion));
        }

        MultiTurnContextResp context = engine.build(history, 100L, "继续查询", now);

        assertTrue(context.isTruncated());
        assertTrue(engine.summarize(context).length() <= MultiTurnContextEngine.MAX_SUMMARY_LENGTH);
        assertFalse(context.getTurns().isEmpty());
        assertEquals(Long.valueOf(10),
                context.getSourceQueryIds().get(context.getSourceQueryIds().size() - 1));
    }

    @Test
    public void shouldIgnoreTurnsFromOtherChats() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp currentChat = successfulQuery(1, now.minusSeconds(2), "当前会话");
        QueryResp otherChat = successfulQuery(2, now.minusSeconds(1), "其他会话");
        otherChat.setChatId(200L);

        MultiTurnContextResp context =
                engine.build(List.of(currentChat, otherChat), 100L, "继续查询", now);

        assertEquals(List.of(1L), context.getSourceQueryIds());
    }

    @Test
    public void shouldKeepLegacySlotsAndAppendResultFactsOnSuccess() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp ok = successfulQuery(1, now.minusSeconds(2), "查询贷款余额");

        MultiTurnContextResp context = engine.build(List.of(ok), 100L, "继续", now);

        MultiTurnContextResp.Turn turn = context.getTurns().get(0);
        assertEquals("SUCCESS", turn.getState());
        assertEquals(Long.valueOf(1), turn.getQueryId());
        assertEquals("查询贷款余额", turn.getQuestion());
        assertEquals("SELECT loan_balance FROM bank_data", turn.getS2sql());
        assertEquals(List.of("loan_balance"), turn.getMetrics());
        assertEquals(List.of("bank_organization"), turn.getDimensions());
        assertTrue(turn.getFilters().get(0).contains("ORG001"));
        assertNotNull(turn.getDateInfo());
        assertTrue(turn.getDateInfo().contains("2026-01-01"));
        assertEquals(List.of("{\"column\":\"loan_balance\",\"direction\":\"DESC\"}"),
                turn.getOrders());
        assertEquals("AGGREGATE", turn.getGranularity());
        assertTrue(turn.getResultTable().contains("loan_balance"));
        assertTrue(turn.getResultTable().contains("42.5"));
        assertEquals("第1轮总结", turn.getTextSummary());
        assertNull(turn.getErrorNote());
    }

    @Test
    public void shouldKeepFailedTurnsWithSafeNoteOnlyAndNoRawErrorLeak() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp ok = successfulQuery(1, now.minusSeconds(3), "成功的问题");
        QueryResp failed = new QueryResp();
        failed.setQuestionId(2L);
        failed.setChatId(100L);
        failed.setCreateTime(Date.from(now.minusSeconds(1)));
        failed.setQueryText("信息不足的问题");
        QueryResult failedResult = new QueryResult();
        failedResult.setQueryState(QueryState.INVALID);
        failedResult.setErrorMsg("SELECT * FROM loan_detail_tbl -- "
                + "SQLSyntaxErrorException: ORA-00904 invalid identifier column org_id");
        failed.setQueryResult(failedResult);

        MultiTurnContextResp context = engine.build(List.of(ok, failed), 100L, "补充提问", now);

        assertEquals(2, context.getUsedRounds());
        MultiTurnContextResp.Turn failedTurn = context.getTurns().get(1);
        assertEquals("FAILED", failedTurn.getState());
        assertEquals("信息不足的问题", failedTurn.getQuestion());
        assertEquals(MultiTurnContextEngine.FAILED_ERROR_NOTE, failedTurn.getErrorNote());
        assertFalse(failedTurn.getErrorNote().contains("SELECT"));
        assertFalse(failedTurn.getErrorNote().contains("loan_detail_tbl"));
        assertFalse(failedTurn.getErrorNote().contains("org_id"));
        assertFalse(failedTurn.getErrorNote().contains("SQLSyntaxErrorException"));
        assertFalse(failedTurn.getErrorNote().contains("ORA-00904"));
        assertNull(failedTurn.getResultTable());
        assertNull(failedTurn.getTextSummary());
        assertNull(failedTurn.getS2sql());
        assertNull(failedTurn.getMetrics());
        assertNull(failedTurn.getDimensions());
        assertNull(failedTurn.getFilters());
        assertNull(failedTurn.getDateInfo());
        assertNull(failedTurn.getOrders());
        assertNull(failedTurn.getGranularity());
        assertEquals("SUCCESS", context.getTurns().get(0).getState());
    }

    @Test
    public void shouldKeepAllNonSuccessTerminalStatesMarkedAsFailed() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        List<QueryResp> history = new ArrayList<>();
        QueryState[] terminalFailures = {QueryState.SEARCH_EXCEPTION, QueryState.EMPTY,
                QueryState.INVALID};
        for (int i = 0; i < terminalFailures.length; i++) {
            QueryResp query = new QueryResp();
            query.setQuestionId((long) (i + 1));
            query.setChatId(100L);
            query.setCreateTime(Date.from(now.minusSeconds(10 - i)));
            query.setQueryText("第" + (i + 1) + "轮失败问题");
            QueryResult result = new QueryResult();
            result.setQueryState(terminalFailures[i]);
            result.setErrorMsg("java.sql.SQLSyntaxErrorException: unknown table bank_secret_tbl");
            query.setQueryResult(result);
            history.add(query);
        }

        MultiTurnContextResp context = engine.build(history, 100L, "继续提问", now);

        assertEquals(3, context.getUsedRounds());
        for (MultiTurnContextResp.Turn turn : context.getTurns()) {
            assertEquals("FAILED", turn.getState());
            assertEquals(MultiTurnContextEngine.FAILED_ERROR_NOTE, turn.getErrorNote());
            assertFalse(turn.getErrorNote().contains("bank_secret_tbl"));
            assertNull(turn.getResultTable());
        }
    }

    @Test
    public void shouldTruncateResultTableRowsAndCells() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp query = successfulQuery(1, now.minusSeconds(2), "查看明细");
        QueryColumn index = new QueryColumn("row_index", "BIGINT", "row_index");
        QueryColumn name = new QueryColumn("name", "VARCHAR", "name");
        QueryColumn remark = new QueryColumn("remark", "VARCHAR", "remark");
        query.getQueryResult().setQueryColumns(List.of(index, name, remark));
        String longRemark = "超长备注内容".repeat(10);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            rows.add(Map.of("row_index", i, "name", "机构" + i, "remark", longRemark));
        }
        query.getQueryResult().setQueryResults(rows);

        MultiTurnContextResp context = engine.build(List.of(query), 100L, "继续", now);

        String table = context.getTurns().get(0).getResultTable();
        assertNotNull(table);
        assertTrue(table.length() <= MultiTurnContextEngine.MAX_TABLE_LENGTH);
        String[] lines = table.split("\n");
        long dataRows = Arrays.stream(lines).filter(line -> line.matches("\\d+\\|.*")).count();
        assertEquals(MultiTurnContextEngine.MAX_TABLE_ROWS, dataRows);
        assertTrue(table.contains("机构10"));
        assertFalse(table.contains("机构11"));
        assertFalse(table.contains(longRemark));
        assertTrue(table
                .contains(longRemark.substring(0, MultiTurnContextEngine.MAX_TABLE_CELL_LENGTH)));
        for (String line : lines) {
            for (String cell : line.split("\\|")) {
                assertTrue(cell.length() <= MultiTurnContextEngine.MAX_TABLE_CELL_LENGTH,
                        "cell too long: " + cell);
            }
        }
    }

    @Test
    public void shouldTruncateResultTableTotalLength() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp query = successfulQuery(1, now.minusSeconds(2), "查看宽表");
        List<QueryColumn> columns = new ArrayList<>();
        String cell = "列名".repeat(12);
        for (int i = 1; i <= 50; i++) {
            columns.add(new QueryColumn(cell + "_" + i, "VARCHAR", cell + "_" + i));
        }
        query.getQueryResult().setQueryColumns(columns);
        query.getQueryResult().setQueryResults(List.of(Map.of(cell + "_1", "value")));

        MultiTurnContextResp context = engine.build(List.of(query), 100L, "继续", now);

        String table = context.getTurns().get(0).getResultTable();
        assertNotNull(table);
        assertEquals(MultiTurnContextEngine.MAX_TABLE_LENGTH, table.length());
    }

    @Test
    public void shouldTruncateTextSummaryToTurnLimit() {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        QueryResp query = successfulQuery(1, now.minusSeconds(2), "查看总结");
        query.getQueryResult().setTextSummary("这段总结非常长".repeat(100));

        MultiTurnContextResp context = engine.build(List.of(query), 100L, "继续", now);

        String summary = context.getTurns().get(0).getTextSummary();
        assertNotNull(summary);
        assertEquals(MultiTurnContextEngine.MAX_TURN_SUMMARY_LENGTH, summary.length());
        assertTrue(summary.startsWith("这段总结非常长"));
    }

    private QueryResp successfulQuery(long id, Instant createTime, String question) {
        QueryResp query = new QueryResp();
        query.setQuestionId(id);
        query.setChatId(100L);
        query.setCreateTime(Date.from(createTime));
        query.setQueryText(question);

        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        QueryColumn column = new QueryColumn("loan_balance", "DECIMAL", "loan_balance");
        result.setQueryColumns(List.of(column));
        result.setQueryResults(List.of(Map.of("loan_balance", 42.5)));
        result.setTextSummary("第" + id + "轮总结");
        query.setQueryResult(result);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setQueryType(QueryType.AGGREGATE);
        parseInfo.getMetrics()
                .add(element(id, "贷款余额", "loan_balance", SchemaElementType.METRIC, 1));
        parseInfo.getDimensions()
                .add(element(id + 100, "机构", "bank_organization", SchemaElementType.DIMENSION, 2));
        QueryFilter filter = new QueryFilter();
        filter.setBizName("bank_organization");
        filter.setValue(String.format("ORG%03d", id));
        parseInfo.getDimensionFilters().add(filter);
        DateConf dateConf = new DateConf();
        dateConf.setStartDate("2026-01-01");
        dateConf.setEndDate("2026-06-30");
        parseInfo.setDateInfo(dateConf);
        parseInfo.getOrders().add(new Order("loan_balance", "DESC"));
        parseInfo.getSqlInfo().setCorrectedS2SQL("SELECT loan_balance FROM bank_data");
        query.setParseInfos(List.of(parseInfo));
        return query;
    }

    private SchemaElement element(long id, String name, String bizName, SchemaElementType type,
            double order) {
        return SchemaElement.builder().id(id).dataSetId(1L).name(name).bizName(bizName).type(type)
                .order(order).build();
    }
}
