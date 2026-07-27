package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
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
}
