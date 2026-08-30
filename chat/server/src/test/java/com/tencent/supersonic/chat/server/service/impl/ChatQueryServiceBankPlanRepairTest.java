package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanTraceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatQueryServiceBankPlanRepairTest {

    @Test
    void retriesDatabaseGrammarFailureWithinTheSameQuestionAndReturnsSuccess() {
        StubService service = new StubService(List.of(
                failed(1, "trace-1", "fingerprint-1", "JDBC_GRAMMAR"), succeeded()));
        service.addRepairParse(parseResponse(20L, 1));
        ChatExecuteReq request = executeRequest();

        QueryResult result = service.executeWithBankPlanRepair(request, storedQuery());

        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertEquals(2, service.executeCount);
        assertEquals(1, service.repairRequests.size());
        ChatParseReq repairRequest = service.repairRequests.get(0);
        assertEquals(20L, repairRequest.getQueryId());
        assertTrue(repairRequest.isInternalBankPlanRepair());
        assertTrue(repairRequest.getBankPlanRepairContext().getToolResultJson()
                .contains("trace-1"));
        assertTrue(repairRequest.getBankPlanRepairContext().getPreviousPlanJson()
                .contains("POINT_QUERY"));
        assertFalse(repairRequest.getBankPlanRepairContext().getToolResultJson()
                .toLowerCase().contains("select "));
        assertSame(service.repairedParse, service.executionParses.get(1));
        @SuppressWarnings("unchecked")
        List<BankPlanTraceEvent> trace = (List<BankPlanTraceEvent>) result.getChatContext()
                .getProperties().get(BankPlanTraceEvent.PROPERTY_KEY);
        assertEquals(2, trace.size());
        assertEquals(BankPlanTraceEvent.Action.REPAIRING, trace.get(0).getAction());
        assertEquals(BankPlanTraceEvent.Action.SUCCEEDED, trace.get(1).getAction());
        assertEquals("POINT_QUERY", trace.get(0).getPlanSummary().getIntent());
        assertFalse(com.tencent.supersonic.common.util.JsonUtil.toString(trace)
                .toLowerCase().contains("select "));
    }

    @Test
    void stopsWhenAPlanFingerprintOrFailureSignatureRepeats() {
        StubService service = new StubService(List.of(
                failed(1, "trace-1", "same-fingerprint", "JDBC_GRAMMAR"),
                failed(2, "trace-1", "same-fingerprint", "JDBC_GRAMMAR"), succeeded()));
        service.addRepairParse(parseResponse(20L, 1));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
        assertEquals(2, service.executeCount);
        assertEquals(1, service.repairRequests.size());
        assertEquals(BankPlanTraceEvent.Action.STOPPED, trace(result).get(1).getAction());
    }

    @Test
    void stopsAfterOneExecutionRepairEvenWhenTheNextFailureDiffers() {
        StubService service = new StubService(List.of(
                failed(1, "trace-1", "fingerprint-1", "JDBC_GRAMMAR"),
                failed(2, "trace-1", "fingerprint-2", "QUERY_GATEWAY"), succeeded()));
        service.addRepairParse(parseResponse(20L, 1));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
        assertEquals(2, service.executeCount);
        assertEquals(1, service.repairRequests.size());
        assertEquals(BankPlanTraceEvent.Action.STOPPED, trace(result).get(1).getAction());
    }

    @Test
    void doesNotAskTheModelToRepairDatabaseAvailabilityFailures() {
        StubService service = new StubService(
                List.of(failed(1, "trace-1", "fingerprint-1", "JDBC_DATA_ACCESS")));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
        assertEquals(1, service.executeCount);
        assertTrue(service.repairRequests.isEmpty());
    }

    @Test
    void repairsTranslationFailuresByRegeneratingTheWholePlan() {
        StubService service = new StubService(List.of(
                failed(1, "trace-1", "fingerprint-1", "TRANSLATION_FAILED"), succeeded()));
        service.addRepairParse(parseResponse(20L, 1));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertEquals(2, service.executeCount);
        assertEquals(1, service.repairRequests.size());
        ChatParseReq repairRequest = service.repairRequests.get(0);
        String feedback = repairRequest.getBankPlanRepairContext().getToolResultJson();
        assertTrue(feedback.contains("TRANSLATION_FAILED"));
        assertTrue(feedback.contains("TRANSLATE"));
        assertEquals(BankPlanTraceEvent.Action.REPAIRING, trace(result).get(0).getAction());
    }

    @Test
    void carriesSqlSafetyRootCauseHintsIntoTheRepairContext() {
        StubService service = new StubService(List.of(
                failed(1, "trace-1", "fingerprint-1", "SQL_SAFETY_POLICY"), succeeded()));
        service.addRepairParse(parseResponse(20L, 1));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertEquals(1, service.repairRequests.size());
        String feedback =
                service.repairRequests.get(0).getBankPlanRepairContext().getToolResultJson();
        assertTrue(feedback.contains("failed_layer=SQL_SAFETY_POLICY"));
        assertTrue(feedback.contains("root_message=opaque database error"));
    }

    @Test
    void doesNotSpendARoundOnEnvironmentFaultsFromExecution() {
        StubService service = new StubService(List.of(
                failed(1, "trace-1", "fingerprint-1", "ENVIRONMENT_FAULT",
                        "Provider auth error: Invalid API key for endpoint")));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
        assertEquals(1, service.executeCount);
        assertTrue(service.repairRequests.isEmpty(),
                "environment faults must terminate without any repair attempt");
        List<BankPlanTraceEvent> events = trace(result);
        assertEquals(BankPlanTraceEvent.Action.STOPPED, events.get(0).getAction());
        assertEquals("ENVIRONMENT_FAULT", events.get(0).getErrorCode());
    }

    @Test
    void retriesResultContractFailuresEvenWhenDatabaseExecutionSucceeded() {
        StubService service = new StubService(List.of(
                resultContractFailure(1, "trace-1", "fingerprint-1"), succeeded()));
        service.addRepairParse(parseResponse(20L, 1));

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SUCCESS, result.getQueryState());
        assertEquals(2, service.executeCount);
        assertEquals(1, service.repairRequests.size());
    }

    @Test
    void keepsTheLastExecutionFailureWhenTheRepairModelIsUnavailable() {
        StubService service = new StubService(
                List.of(failed(1, "trace-1", "fingerprint-1", "JDBC_GRAMMAR")));
        service.repairFailure = new IllegalStateException("model unavailable: secret endpoint");

        QueryResult result = service.executeWithBankPlanRepair(executeRequest(), storedQuery());

        assertEquals(QueryState.SEARCH_EXCEPTION, result.getQueryState());
        assertEquals(1, service.executeCount);
        assertEquals(1, service.repairRequests.size());
        BankPlanTraceEvent traceEvent = trace(result).get(0);
        assertEquals(BankPlanTraceEvent.Action.STOPPED, traceEvent.getAction());
        assertFalse(traceEvent.getActionMessage().contains("secret endpoint"));
    }

    private static QueryResult failed(int attempt, String traceId, String fingerprint,
            String errorCode) {
        return failed(attempt, traceId, fingerprint, errorCode, "opaque database error");
    }

    private static QueryResult failed(int attempt, String traceId, String fingerprint,
            String errorCode, String errorMessage) {
        BankPlanToolResult.Stage stage = switch (errorCode) {
            case "SQL_SAFETY_POLICY" -> BankPlanToolResult.Stage.SQL_SAFETY;
            case "QUERY_GATEWAY" -> BankPlanToolResult.Stage.DATABASE_PREPARE;
            case "TRANSLATION_FAILED" -> BankPlanToolResult.Stage.TRANSLATE;
            default -> BankPlanToolResult.Stage.DATABASE_EXECUTE;
        };
        BankPlanToolResult toolResult = BankPlanToolResult.failed(attempt, traceId, fingerprint,
                stage, errorCode, Map.of(),
                // Same hint shape SqlExecutor persists for execution-stage failures.
                List.of("failed_layer=" + errorCode, "root_message=" + errorMessage,
                        "regenerate the complete plan"));
        if (errorMessage != null) {
            toolResult.setMessage(errorMessage);
        }
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("version", "1.0");
        plan.put("intent", "POINT_QUERY");
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY, plan);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SEARCH_EXCEPTION);
        result.setErrorMsg("opaque database error");
        result.setChatContext(parseInfo);
        return result;
    }

    private static QueryResult succeeded() {
        BankPlanToolResult toolResult = BankPlanToolResult.started(2, "trace-1", "fingerprint-2",
                "STRUCT", List.of("aggregate_value"))
                .succeed(BankPlanToolResult.Stage.SQL_SAFETY)
                .succeed(BankPlanToolResult.Stage.DATABASE_PREPARE)
                .succeed(BankPlanToolResult.Stage.DATABASE_EXECUTE)
                .complete(List.of("aggregate_value"), List.of(Map.of("aggregate_value", 1)));
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("version", "1.0", "intent", "POINT_QUERY"));
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        return result;
    }

    private static QueryResult resultContractFailure(int attempt, String traceId,
            String fingerprint) {
        BankPlanToolResult toolResult = BankPlanToolResult.failed(attempt, traceId, fingerprint,
                BankPlanToolResult.Stage.RESULT_SEMANTIC, "RESULT_CONTRACT_MISMATCH", Map.of(),
                List.of("regenerate the complete plan"));
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
        parseInfo.getProperties().put(BankPlanToolResult.PLAN_PROPERTY_KEY,
                Map.of("version", "1.0", "intent", "POINT_QUERY"));
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setChatContext(parseInfo);
        return result;
    }

    private static ChatParseResp parseResponse(long queryId, int parseId) {
        ChatParseResp response = new ChatParseResp(queryId);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setId(parseId);
        response.setSelectedParses(List.of(parseInfo));
        return response;
    }

    private static ChatExecuteReq executeRequest() {
        return ChatExecuteReq.builder().queryId(20L).chatId(10).agentId(7).parseId(1)
                .queryText("trusted question").user(User.get(2L, "analyst")).build();
    }

    private static ChatQueryDO storedQuery() {
        ChatQueryDO storedQuery = new ChatQueryDO();
        storedQuery.setQuestionId(20L);
        storedQuery.setChatId(10L);
        storedQuery.setAgentId(7);
        storedQuery.setQueryText("trusted question");
        return storedQuery;
    }

    @SuppressWarnings("unchecked")
    private static List<BankPlanTraceEvent> trace(QueryResult result) {
        return (List<BankPlanTraceEvent>) result.getChatContext().getProperties()
                .get(BankPlanTraceEvent.PROPERTY_KEY);
    }

    private static final class StubService extends ChatQueryServiceImpl {
        private final Deque<QueryResult> results;
        private final Deque<ChatParseResp> repairParses = new ArrayDeque<>();
        private final List<ChatParseReq> repairRequests = new ArrayList<>();
        private final List<SemanticParseInfo> executionParses = new ArrayList<>();
        private int executeCount;
        private RuntimeException repairFailure;
        private SemanticParseInfo repairedParse;

        private StubService(List<QueryResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        private void addRepairParse(ChatParseResp response) {
            repairParses.add(response);
            repairedParse = response.getSelectedParses().get(0);
        }

        @Override
        QueryResult executeOnce(ChatExecuteReq request, ChatQueryDO storedQuery,
                SemanticParseInfo parseOverride) {
            executeCount++;
            executionParses.add(parseOverride);
            return results.removeFirst();
        }

        @Override
        public ChatParseResp parse(ChatParseReq request) {
            repairRequests.add(request);
            if (repairFailure != null) {
                throw repairFailure;
            }
            return repairParses.removeFirst();
        }
    }
}
