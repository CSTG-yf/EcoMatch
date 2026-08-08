package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlError;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMSqlParserTest {

    @Test
    void shouldTerminateConstrainedPlanAsFailedWhenCompilationFails() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(2);

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
        verify(requestService, times(1)).runText2SQL(llmReq);
    }
}
