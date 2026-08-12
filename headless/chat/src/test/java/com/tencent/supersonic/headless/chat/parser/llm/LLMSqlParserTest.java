package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlError;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMSqlParserTest {

    @Test
    void compilationRepairExplainsRankingAndProvinceAverageConflictWithoutRawExceptionText() {
        List<String> hints = LLMSqlParser.compilationCorrectionHints(
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION, """
                        {"intent":"RANKING","filters":[{"field":"benchmark",
                        "operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}]}
                        """);

        assertEquals(List.of("当前计划是“全省排名”而不是“全省均值”比较：删除 "
                + "benchmark/COMPARE/PROVINCE_AVERAGE，令 filters=[]；保留 intent=RANKING "
                + "和 bank_organization 维度后，重新输出完整 BankQueryPlan。"), hints);
    }

    @Test
    void shouldRetryCompilationOnceWithSanitizedToolFeedbackThenStopOnRepeatedFailure() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(3);

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = new LLMReq();
        llmReq.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq)).thenThrow(new BankPlanCompilationException(
                BankPlanCompilationException.Reason.DIMENSION_UNAVAILABLE, "opaque-details"));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);

            new LLMSqlParser().parse(queryCtx);
        }

        assertEquals(ParseResp.ParseState.FAILED, parseResp.getState());
        assertTrue(BankNl2SqlError.isTerminalParserError(parseResp.getErrorMsg()));
        verify(requestService, times(2)).runText2SQL(llmReq);
        assertEquals(BankPlanToolResult.Stage.COMPILE,
                llmReq.getBankPlanToolResult().getFailedStage());
        assertEquals("DIMENSION_UNAVAILABLE", llmReq.getBankPlanToolResult().getErrorCode());
        assertTrue(!llmReq.getBankPlanToolResult().toRepairFeedback().contains("opaque-details"));
    }
}
