package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankRequestContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataInterpretProcessorTest {

    @AfterEach
    void resetCache() {
        DataInterpretProcessor.resetStreamingResults();
    }

    @Test
    void boundsActiveStreamingInterpretationsAndExpiresAbandonedEntries() {
        DataInterpretProcessor.initializeStreamingResult(1L);
        assertThrows(IllegalStateException.class,
                () -> DataInterpretProcessor.initializeStreamingResult(1L));
        DataInterpretProcessor.resetStreamingResults();

        for (long queryId =
                1; queryId <= DataInterpretProcessor.MAX_ACTIVE_INTERPRETATIONS; queryId++) {
            DataInterpretProcessor.initializeStreamingResult(queryId);
        }

        assertThrows(IllegalStateException.class,
                () -> DataInterpretProcessor.initializeStreamingResult(1_001L));

        DataInterpretProcessor.cleanupExpiredEntries(Long.MAX_VALUE);
        assertTrue(DataInterpretProcessor.getResultCache().isEmpty());
        DataInterpretProcessor.initializeStreamingResult(1_001L);
    }

    @Test
    void discardsOversizedStreamingSummaryWithoutPersistingPartialText() {
        DataInterpretProcessor.initializeStreamingResult(1L);
        DataInterpretProcessor.appendStreamingToken(1L,
                "x".repeat(DataInterpretProcessor.MAX_SUMMARY_CHARACTERS));
        DataInterpretProcessor.appendStreamingToken(1L, "overflow");

        assertEquals("", DataInterpretProcessor.getTextSummary(1L));
        assertNull(DataInterpretProcessor.completeStreamingResult(1L));
    }

    @Test
    void completesSummaryOnceAndReturnsDefensiveCacheSnapshot() {
        DataInterpretProcessor.initializeStreamingResult(1L);
        DataInterpretProcessor.appendStreamingToken(1L, "受控业务摘要");

        DataInterpretProcessor.getResultCache().get(1L).append("tampered");

        assertEquals("受控业务摘要", DataInterpretProcessor.completeStreamingResult(1L));
        assertNull(DataInterpretProcessor.completeStreamingResult(1L));
    }

    @Test
    void usesRewrittenQuestionWithoutDuplicatingResultText() {
        ExecuteContext context =
                new ExecuteContext(ChatExecuteReq.builder().queryText("原始问题").build());
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put("CONTEXT", Map.of("queryText", "改写后的业务问题"));
        context.setParseInfo(parseInfo);

        assertEquals("改写后的业务问题", DataInterpretProcessor.resolveQuestion(context));

        parseInfo.getProperties().put("CONTEXT", Map.of("queryText", " "));
        assertEquals("原始问题", DataInterpretProcessor.resolveQuestion(context));
    }

    @Test
    void boundsInterpretationQuestionAndResultTextBeforeModelInvocation() {
        assertTrue(DataInterpretProcessor.isInterpretationInputWithinLimits(
                "q".repeat(DataInterpretProcessor.MAX_QUESTION_CHARACTERS),
                "d".repeat(DataInterpretProcessor.MAX_RESULT_TEXT_CHARACTERS)));
        assertFalse(DataInterpretProcessor.isInterpretationInputWithinLimits(
                "q".repeat(DataInterpretProcessor.MAX_QUESTION_CHARACTERS + 1), "data"));
        assertFalse(DataInterpretProcessor.isInterpretationInputWithinLimits("question",
                "d".repeat(DataInterpretProcessor.MAX_RESULT_TEXT_CHARACTERS + 1)));
        assertFalse(DataInterpretProcessor.isInterpretationInputWithinLimits("question", null));
    }

    @Test
    void doesNotCompeteWithValidatedBankFinalAnswer() {
        ExecuteContext context = new ExecuteContext(
                ChatExecuteReq.builder().queryText("银行存款是多少？").build());
        Agent agent = new Agent();
        agent.setChatAppConfig(Map.of(
                DataInterpretProcessor.APP_KEY, ChatApp.builder().enable(true).build(),
                BankFinalAnswerProcessor.APP_KEY, ChatApp.builder().enable(true).build()));
        context.setAgent(agent);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY, Map.of("intent", "POINT_QUERY"));
        parseInfo.getProperties().put(BankRequestContract.PROPERTY_KEY, Map.of("action", "EXECUTE"));
        context.setParseInfo(parseInfo);

        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setTextResult("结果");
        context.setResponse(result);

        assertTrue(DataInterpretProcessor.bankFinalAnswerOwnsSummary(context));
        assertFalse(new DataInterpretProcessor().accept(context));
    }
}
