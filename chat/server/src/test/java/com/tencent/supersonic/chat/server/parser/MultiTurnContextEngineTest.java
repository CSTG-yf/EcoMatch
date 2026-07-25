package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.response.MultiTurnContextResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Order;
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
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private QueryResp successfulQuery(long id, Instant createTime, String question) {
        QueryResp query = new QueryResp();
        query.setQuestionId(id);
        query.setChatId(100L);
        query.setCreateTime(Date.from(createTime));
        query.setQueryText(question);

        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
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
