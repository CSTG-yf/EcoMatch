package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.MultiTurnContextResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.QueryType;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SchemaMapInfo;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.MapResp;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NL2SQLParserTest {

    @Test
    void forwardsBankRoutingAttemptTelemetry() {
        ParseResp source = new ParseResp("safe");
        source.setBankRoutingAttemptTelemetry(new ParseResp.BankRoutingAttemptTelemetry(
                true, false, ParseResp.BankRoutingSqlGenType.ONE_PASS_SELF_CONSISTENCY, false,
                ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                SqlErrorType.JOIN_ERROR));
        ChatParseResp target = new ChatParseResp(1L);

        NL2SQLParser.copyParseResponse(source, target);

        ParseResp.BankRoutingAttemptTelemetry telemetry =
                target.getBankRoutingAttemptTelemetry();
        assertTrue(telemetry.isBankConstrainedPlanEnabled());
        assertFalse(telemetry.isBankDatasetQualified());
        assertEquals(ParseResp.BankRoutingSqlGenType.ONE_PASS_SELF_CONSISTENCY,
                telemetry.getSelectedSqlGenType());
        assertFalse(telemetry.isLlmCandidateCreated());
        assertEquals(ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                telemetry.getCandidateRejectionState());
        assertEquals(SqlErrorType.JOIN_ERROR, telemetry.getCandidateValidationErrorType());
    }

    @Test
    void preservesMissingBankRoutingAttemptTelemetry() {
        ParseResp source = new ParseResp("safe");
        ChatParseResp target = new ChatParseResp(1L);
        target.setBankRoutingAttemptTelemetry(new ParseResp.BankRoutingAttemptTelemetry(
                true, true, ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, true));

        NL2SQLParser.copyParseResponse(source, target);

        assertNull(target.getBankRoutingAttemptTelemetry());
    }

    @Test
    void keepsOriginalQueryWhenModelReturnsSameIgnoringCaseAndWhitespace() {
        String question = "各省分行存款余额是多少";
        for (String same : List.of("SAME", "same", " Same ", " \tSame\n ")) {
            RewriteOutcome outcome = rewriteWithModelText(question, same);

            assertEquals(question, outcome.parseContext.getRequest().getQueryText());
            assertEquals(question, outcome.queryNLReq.getQueryText());
            assertNull(outcome.parseContext.getResponse().getMultiTurnContext()
                    .getRewrittenQuery());
            verify(outcome.model).generate(any(ChatMessage.class));
        }
    }

    @Test
    void keepsOriginalQueryWhenModelReturnsBlank() {
        String question = "各省分行存款余额是多少";
        for (String blank : List.of("", "   ", "\t\n")) {
            RewriteOutcome outcome = rewriteWithModelText(question, blank);

            assertEquals(question, outcome.parseContext.getRequest().getQueryText());
            assertEquals(question, outcome.queryNLReq.getQueryText());
            assertNull(outcome.parseContext.getResponse().getMultiTurnContext()
                    .getRewrittenQuery());
        }
    }

    @Test
    void keepsOriginalQueryWhenModelThrows() {
        String question = "各省分行存款余额是多少";
        RewriteOutcome outcome =
                rewriteThrowing(question, new RuntimeException("model boom"));

        assertEquals(question, outcome.parseContext.getRequest().getQueryText());
        assertEquals(question, outcome.queryNLReq.getQueryText());
        assertNull(outcome.parseContext.getResponse().getMultiTurnContext()
                .getRewrittenQuery());
    }

    @Test
    void appliesNormalModelRewriteToRequestQueryAndContext() {
        String question = "各省分行存款余额是多少";
        String rewritten = " 2024年各省分行存款余额是多少 ";
        RewriteOutcome outcome = rewriteWithModelText(question, rewritten);

        assertEquals(rewritten.trim(), outcome.parseContext.getRequest().getQueryText());
        assertEquals(rewritten.trim(), outcome.queryNLReq.getQueryText());
        assertEquals(rewritten.trim(), outcome.parseContext.getResponse().getMultiTurnContext()
                .getRewrittenQuery());
    }

    @Test
    void stillInvokesModelForSelfContainedStyleQuestion() {
        String question = "2024年各省分行存款余额排名";
        String rewritten = "2024年各省分行存款余额排名情况";
        SchemaElement metric = SchemaElement.builder().name("存款余额")
                .bizName("deposit_balance").type(SchemaElementType.METRIC).build();
        SchemaElementMatch match = SchemaElementMatch.builder().element(metric).word("存款余额")
                .similarity(1.0).build();
        SchemaMapInfo mapInfo = new SchemaMapInfo();
        mapInfo.setMatchedElements(1L, List.of(match));
        MapResp mapResp = new MapResp(question, mapInfo);

        RewriteOutcome outcome = rewrite(question, mapResp, model -> when(
                model.generate(any(ChatMessage.class)))
                .thenReturn(Response.from(AiMessage.from(rewritten))));

        verify(outcome.model).generate(any(ChatMessage.class));
        assertEquals(rewritten, outcome.parseContext.getRequest().getQueryText());
    }

    @Test
    void exposesNoSelfContainedShortCircuitMembers() {
        Class<?> clazz = NL2SQLParser.class;
        assertFalse(Arrays.stream(clazz.getDeclaredMethods()).map(Method::getName)
                .anyMatch("isSelfContained"::equals));
        assertFalse(Arrays.stream(clazz.getDeclaredFields()).map(Field::getName)
                .anyMatch(name -> "DATE_PATTERN".equals(name)
                        || "PROVINCE_WIDE_PATTERN".equals(name)));
    }

    private RewriteOutcome rewriteWithModelText(String question, String modelText) {
        return rewrite(question, new MapResp(question, new SchemaMapInfo()),
                model -> when(model.generate(any(ChatMessage.class)))
                        .thenReturn(Response.from(AiMessage.from(modelText))));
    }

    private RewriteOutcome rewriteThrowing(String question, RuntimeException failure) {
        return rewrite(question, new MapResp(question, new SchemaMapInfo()),
                model -> when(model.generate(any(ChatMessage.class))).thenThrow(failure));
    }

    private RewriteOutcome rewrite(String question, MapResp mapResp,
            Consumer<ChatLanguageModel> modelStub) {
        ChatParseReq request = ChatParseReq.builder().queryText(question).chatId(10)
                .queryId(100L).user(User.get(1L, "alice")).build();
        ChatParseResp response = new ChatParseResp(100L);
        ParseContext parseContext = new ParseContext(request, response);
        Agent agent = new Agent();
        agent.setChatAppConfig(Map.of(NL2SQLParser.APP_KEY_MULTI_TURN,
                ChatApp.builder().name("多轮对话改写").prompt("Rewrite {{current_question}}")
                        .enable(true).build()));
        parseContext.setAgent(agent);
        QueryNLReq queryNLReq = new QueryNLReq();
        queryNLReq.setQueryText(question);

        ChatManageService chatManageService = mock(ChatManageService.class);
        when(chatManageService.getChatQueries(any(), any())).thenReturn(history());
        ChatLayerService chatLayerService = mock(ChatLayerService.class);
        when(chatLayerService.map(any(QueryNLReq.class))).thenReturn(mapResp);
        ChatModelService chatModelService = mock(ChatModelService.class);
        when(chatModelService.getChatModel(any())).thenReturn(null);
        ChatLanguageModel chatLanguageModel = mock(ChatLanguageModel.class);
        modelStub.accept(chatLanguageModel);

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class);
                MockedStatic<ModelProvider> modelProvider =
                        mockStatic(ModelProvider.class)) {
            contextUtils.when(() -> ContextUtils.getBean(ChatManageService.class))
                    .thenReturn(chatManageService);
            contextUtils.when(() -> ContextUtils.getBean(ChatLayerService.class))
                    .thenReturn(chatLayerService);
            contextUtils.when(() -> ContextUtils.getBean(ChatModelService.class))
                    .thenReturn(chatModelService);
            modelProvider.when(() -> ModelProvider.getChatModel(any()))
                    .thenReturn(chatLanguageModel);
            new NL2SQLParser().rewriteMultiTurn(parseContext, queryNLReq);
        }
        return new RewriteOutcome(parseContext, queryNLReq, chatLanguageModel);
    }

    private List<QueryResp> history() {
        QueryResp query = new QueryResp();
        query.setQuestionId(90L);
        query.setChatId(10L);
        query.setCreateTime(new Date());
        query.setQueryText("上个月的存款余额");
        QueryResult queryResult = new QueryResult();
        queryResult.setQueryState(QueryState.SUCCESS);
        query.setQueryResult(queryResult);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setQueryType(QueryType.AGGREGATE);
        query.setParseInfos(List.of(parseInfo));
        return List.of(query);
    }

    private static final class RewriteOutcome {
        final ParseContext parseContext;
        final QueryNLReq queryNLReq;
        final ChatLanguageModel model;

        RewriteOutcome(ParseContext parseContext, QueryNLReq queryNLReq,
                ChatLanguageModel model) {
            this.parseContext = parseContext;
            this.queryNLReq = queryNLReq;
            this.model = model;
        }
    }
}
