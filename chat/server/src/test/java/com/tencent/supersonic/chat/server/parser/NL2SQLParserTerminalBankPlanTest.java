package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.service.impl.ExemplarServiceImpl;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.MapModeEnum;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the fail-closed handling of terminal bank constrained-plan errors in
 * {@link NL2SQLParser}: the first LLM doParse failure carrying the {@code [BANK_CONSTRAINED_PLAN]}
 * prefix must never trigger the MapModeEnum.ALL fallback, and the internal prefix must be stripped
 * from the final error message. Non-terminal failures keep the existing ALL fallback.
 */
class NL2SQLParserTerminalBankPlanTest {

    private static final String BANK_USER_MESSAGE = "未能可靠识别该银行指标查询，请明确机构、指标和时间范围后重试。";
    private static final String BANK_PARSER_ERROR = "[BANK_CONSTRAINED_PLAN]" + BANK_USER_MESSAGE;

    private ChatLayerService chatLayerService;
    private ParseContext parseContext;

    @BeforeEach
    void setUp() {
        chatLayerService = mock(ChatLayerService.class);

        ExemplarServiceImpl exemplarService = mock(ExemplarServiceImpl.class);
        when(exemplarService.recallExemplars(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        EmbeddingConfig embeddingConfig = mock(EmbeddingConfig.class);
        when(embeddingConfig.getMemoryCollectionName(any())).thenReturn("memory");

        ParserConfig parserConfig = mock(ParserConfig.class);
        when(parserConfig.getParameterValue(ParserConfig.PARSER_EXEMPLAR_RECALL_NUMBER))
                .thenReturn("10");

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(ChatLayerService.class)).thenReturn(chatLayerService);
        when(context.getBean(ExemplarServiceImpl.class)).thenReturn(exemplarService);
        when(context.getBean(EmbeddingConfig.class)).thenReturn(embeddingConfig);
        when(context.getBean(ParserConfig.class)).thenReturn(parserConfig);
        // ContextUtils.setApplicationContext is an instance method, so it must be invoked on a
        // new ContextUtils instance instead of statically.
        new ContextUtils().setApplicationContext(context);

        Agent agent = new Agent();
        agent.setId(1);

        ChatParseReq request = ChatParseReq.builder().queryId(1L).queryText("查询贷款余额")
                .selectedParse(new SemanticParseInfo()).build();
        parseContext = new ParseContext(request, new ChatParseResp(1L));
        parseContext.setAgent(agent);
    }

    @AfterEach
    void tearDown() {
        new ContextUtils().setApplicationContext(null);
    }

    @Test
    void terminalBankErrorSkipsAllFallbackAndStripsPrefix() {
        ParseResp failed = new ParseResp("查询贷款余额");
        failed.setState(ParseResp.ParseState.FAILED);
        failed.setErrorMsg(BANK_PARSER_ERROR);
        when(chatLayerService.parse(any(QueryNLReq.class))).thenReturn(failed);

        new NL2SQLParser().parse(parseContext);

        ArgumentCaptor<QueryNLReq> captor = ArgumentCaptor.forClass(QueryNLReq.class);
        verify(chatLayerService, times(1)).parse(captor.capture());
        QueryNLReq onlyCall = captor.getValue();
        // no MapModeEnum.ALL fallback was issued
        assertEquals(MapModeEnum.STRICT, onlyCall.getMapModeEnum());
        assertEquals(ParseResp.ParseState.FAILED, parseContext.getResponse().getState());
        // internal prefix stripped, only the user-facing message remains
        assertEquals(BANK_USER_MESSAGE, parseContext.getResponse().getErrorMsg());
        assertTrue(parseContext.getResponse().isTerminalError());
    }

    @Test
    void nonTerminalFailureStillFallsBackToAllMapping() {
        ParseResp first = new ParseResp("查询贷款余额");
        first.setState(ParseResp.ParseState.FAILED);
        first.setErrorMsg("internal mapping failure");
        ParseResp second = new ParseResp("查询贷款余额");
        second.setState(ParseResp.ParseState.COMPLETED);
        when(chatLayerService.parse(any(QueryNLReq.class))).thenReturn(first, second);

        new NL2SQLParser().parse(parseContext);

        ArgumentCaptor<QueryNLReq> captor = ArgumentCaptor.forClass(QueryNLReq.class);
        verify(chatLayerService, times(2)).parse(captor.capture());
        List<QueryNLReq> calls = captor.getAllValues();
        assertEquals(2, calls.size());
        // the second attempt uses the ALL mapping and drops the selected parse
        assertEquals(MapModeEnum.ALL, calls.get(1).getMapModeEnum());
        assertNull(calls.get(1).getSelectedParseInfo());
        assertEquals(ParseResp.ParseState.COMPLETED, parseContext.getResponse().getState());
    }
}
