package com.tencent.supersonic.chat.server.executor;

import com.tencent.supersonic.chat.api.pojo.enums.MemoryStatus;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ChatContext;
import com.tencent.supersonic.chat.server.pojo.ChatMemory;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.service.ChatContextService;
import com.tencent.supersonic.chat.server.service.MemoryService;
import com.tencent.supersonic.chat.server.util.ResultFormatter;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.corrector.LLMPhysicalSqlCorrector;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankEnvironmentFaultClassifier;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SqlExecutor implements ChatQueryExecutor {

    private static final Set<String> EXECUTION_FAILURE_LAYERS = Set.of("SQL_SAFETY_POLICY",
            "QUERY_GATEWAY", "JDBC_GRAMMAR", "JDBC_DATA_ACCESS", "JDBC_OTHER");

    @Override
    public boolean accept(ExecuteContext executeContext) {
        return true;
    }

    @SneakyThrows
    @Override
    public QueryResult execute(ExecuteContext executeContext) {
        QueryResult queryResult = doExecute(executeContext);

        if (queryResult != null) {
            String textResult = ResultFormatter.transform2TextNew(queryResult.getQueryColumns(),
                    queryResult.getQueryResults());
            queryResult.setTextResult(textResult);

            if (queryResult.getQueryState().equals(QueryState.SUCCESS)
                    && queryResult.getQueryMode().equals(LLMSqlQuery.QUERY_MODE)) {
                Text2SQLExemplar exemplar =
                        JsonUtil.toObject(
                                JsonUtil.toString(executeContext.getParseInfo().getProperties()
                                        .get(Text2SQLExemplar.PROPERTY_KEY)),
                                Text2SQLExemplar.class);

                MemoryService memoryService = ContextUtils.getBean(MemoryService.class);
                memoryService.createMemory(ChatMemory.builder().queryId(queryResult.getQueryId())
                        .agentId(executeContext.getAgent().getId()).status(MemoryStatus.PENDING)
                        .question(exemplar.getQuestion()).sideInfo(exemplar.getSideInfo())
                        .dbSchema(exemplar.getDbSchema()).s2sql(exemplar.getSql())
                        .createdBy(executeContext.getRequest().getUser().getName())
                        .updatedBy(executeContext.getRequest().getUser().getName())
                        .createdAt(new Date()).build());
            }
        }

        return queryResult;
    }

    @SneakyThrows
    private QueryResult doExecute(ExecuteContext executeContext) {
        SemanticLayerService semanticLayer = ContextUtils.getBean(SemanticLayerService.class);
        ChatContextService chatContextService = ContextUtils.getBean(ChatContextService.class);

        ChatContext chatCtx =
                chatContextService.getOrCreateContext(executeContext.getRequest().getChatId());
        SemanticParseInfo parseInfo = executeContext.getParseInfo();
        if (Objects.isNull(parseInfo.getSqlInfo())
                || StringUtils.isBlank(parseInfo.getSqlInfo().getCorrectedS2SQL())) {
            return null;
        }

        // Keep the S2SQL request for model-scope authorization; querySQL remains the physical SQL
        // consumed by S2SemanticLayerService when it builds the executable query statement.
        String scopeSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        String finalSql = StringUtils.isNotBlank(parseInfo.getSqlInfo().getQuerySQL())
                ? parseInfo.getSqlInfo().getQuerySQL()
                : scopeSql;

        QuerySqlReq sqlReq = QuerySqlReq.builder().sql(scopeSql).build();
        sqlReq.setSqlInfo(parseInfo.getSqlInfo());
        sqlReq.setDataSetId(parseInfo.getDataSetId());
        // resultOnly is the trusted, server-side official evaluation mode. It must capture the
        // actual typed facts rather than an authorization-masked presentation response.
        sqlReq.setNeedAuth(!executeContext.getRequest().isResultOnly());
        sqlReq.setTrustedCompiledSql(
                StringUtils.isBlank(parseInfo.getSqlInfo().getCorrectedQuerySQL()));

        long startTime = System.currentTimeMillis();
        QueryResult queryResult = new QueryResult();
        queryResult.setQueryId(executeContext.getRequest().getQueryId());
        queryResult.setChatContext(parseInfo);
        queryResult.setQueryMode(parseInfo.getQueryMode());
        SemanticQueryResp queryResp =
                semanticLayer.queryByReq(sqlReq, executeContext.getRequest().getUser());
        String originalError = queryResp == null ? null : queryResp.getErrorMsg();
        boolean repairAttempted = false;
        if (queryResp != null && StringUtils.isNotBlank(originalError)
                && LLMSqlQuery.QUERY_MODE.equals(parseInfo.getQueryMode())
                && executeContext.getAgent() != null
                && shouldAttemptPhysicalSqlRepair(parseInfo)) {
            String repairedSql = LLMPhysicalSqlCorrector.repairExecutionError(
                    executeContext.getAgent().getChatAppConfig()
                            .get(LLMPhysicalSqlCorrector.EXECUTION_APP_KEY),
                    executeContext.getRequest().getQueryText(), finalSql, originalError);
            if (StringUtils.isNotBlank(repairedSql)) {
                repairAttempted = true;
                parseInfo.getSqlInfo().setCorrectedQuerySQL(repairedSql);
                parseInfo.getSqlInfo().setQuerySQL(repairedSql);
                sqlReq.setTrustedCompiledSql(false);
                queryResp = semanticLayer.queryByReq(sqlReq, executeContext.getRequest().getUser());
                finalSql = repairedSql;
            }
        }
        queryResult.setQueryTimeCost(System.currentTimeMillis() - startTime);
        persistBankPlanToolExecution(parseInfo, queryResp);
        if (queryResp != null) {
            queryResult.setQueryAuthorization(queryResp.getQueryAuthorization());
            queryResult.setQuerySql(finalSql);
            queryResult.setQueryResults(queryResp.getResultList());
            queryResult.setQueryColumns(queryResp.getColumns());
            queryResult.setDataMasked(queryResp.isDataMasked());
            queryResult.setMaskedColumns(queryResp.getMaskedColumns());
            queryResult.setErrorMsg(queryResp.getErrorMsg());
            if (StringUtils.isBlank(queryResp.getErrorMsg())) {
                queryResult.setQueryState(QueryState.SUCCESS);
                if (repairAttempted) {
                    persistExecutionTelemetry(parseInfo, queryResp, true, true);
                }
                chatCtx.setParseInfo(parseInfo);
                chatContextService.updateContext(chatCtx);
            } else {
                queryResult.setQueryState(QueryState.SEARCH_EXCEPTION);
                persistExecutionTelemetry(parseInfo, queryResp, repairAttempted, false);
            }
        } else {
            queryResult.setQueryState(QueryState.INVALID);
        }

        return queryResult;
    }

    /**
     * Bank constrained plans must be repaired by regenerating the plan and recompiling it. They
     * must never be handed to the legacy physical-SQL corrector, which would let a model mutate a
     * compiler-produced SQL string outside the plan contract. Other query modes retain the
     * existing optional physical-SQL correction behavior.
     */
    static boolean shouldAttemptPhysicalSqlRepair(SemanticParseInfo parseInfo) {
        if (parseInfo == null || parseInfo.getProperties() == null) {
            return true;
        }
        // Presence of the marker is authoritative. A malformed marker must fail closed rather
        // than reopening the legacy physical-SQL model path.
        return !parseInfo.getProperties().containsKey(BankPlanToolResult.PROPERTY_KEY);
    }

    private void persistExecutionTelemetry(SemanticParseInfo parseInfo,
            SemanticQueryResp queryResp, boolean repairAttempted, boolean repaired) {
        Map<String, Object> telemetry = new LinkedHashMap<>();
        Map<String, Object> responseTelemetry = queryResp.getExecutionTelemetry();
        Object failureLayer = responseTelemetry == null
                ? null : responseTelemetry.get("failureLayer");
        if (failureLayer instanceof String layer && EXECUTION_FAILURE_LAYERS.contains(layer)) {
            telemetry.put("failureLayer", layer);
        }
        telemetry.put("repairAttempted", repairAttempted);
        telemetry.put("repaired", repaired);
        parseInfo.getProperties().put("executionTelemetry", telemetry);
    }

    private void persistBankPlanToolExecution(SemanticParseInfo parseInfo,
            SemanticQueryResp queryResp) {
        BankPlanToolResult toolResult = BankPlanToolResult
                .from(parseInfo.getProperties().get(BankPlanToolResult.PROPERTY_KEY));
        if (toolResult == null) {
            return;
        }
        if (queryResp != null && StringUtils.isBlank(queryResp.getErrorMsg())) {
            toolResult.succeed(BankPlanToolResult.Stage.SQL_SAFETY)
                    .succeed(BankPlanToolResult.Stage.DATABASE_PREPARE)
                    .succeed(BankPlanToolResult.Stage.DATABASE_EXECUTE);
            parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
            return;
        }

        String failureLayer = allowlistedFailureLayer(queryResp);
        BankPlanToolResult.Stage stage = failureStage(failureLayer);
        if (stage.ordinal() > BankPlanToolResult.Stage.SQL_SAFETY.ordinal()) {
            toolResult.succeed(BankPlanToolResult.Stage.SQL_SAFETY);
        }
        if (stage.ordinal() > BankPlanToolResult.Stage.DATABASE_PREPARE.ordinal()) {
            toolResult.succeed(BankPlanToolResult.Stage.DATABASE_PREPARE);
        }
        // Unclassified failures are checked for provider/infra outage signatures first: those get
        // ENVIRONMENT_FAULT, which is intentionally absent from the repair whitelist, so the loop
        // stops instead of spending another model round on a dead endpoint.
        String rawErrorMsg = queryResp == null ? null : queryResp.getErrorMsg();
        String errorCode = failureLayer;
        if (errorCode == null) {
            errorCode = BankEnvironmentFaultClassifier.isEnvironmentFault(null, rawErrorMsg)
                    ? BankEnvironmentFaultClassifier.CODE
                    : "DATABASE_EXECUTION_FAILED";
        }
        toolResult.fail(stage, errorCode, Map.of(),
                correctionHints(stage, errorCode, rawErrorMsg));
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
    }

    private String allowlistedFailureLayer(SemanticQueryResp queryResp) {
        if (queryResp == null || queryResp.getExecutionTelemetry() == null) {
            return null;
        }
        Object value = queryResp.getExecutionTelemetry().get("failureLayer");
        return value instanceof String layer && EXECUTION_FAILURE_LAYERS.contains(layer) ? layer
                : null;
    }

    private BankPlanToolResult.Stage failureStage(String failureLayer) {
        if ("SQL_SAFETY_POLICY".equals(failureLayer)) {
            return BankPlanToolResult.Stage.SQL_SAFETY;
        }
        if ("QUERY_GATEWAY".equals(failureLayer)) {
            return BankPlanToolResult.Stage.DATABASE_PREPARE;
        }
        return BankPlanToolResult.Stage.DATABASE_EXECUTE;
    }

    /**
     * Dynamic root-cause channel for the repair loop (the toolResult {@code message} field stays
     * the generic contract text on purpose). The hints carry the failure layer plus the truncated
     * raw execution error — SqlSafetyPolicy rejections are static-constant texts and JDBC
     * diagnostics only reference schema-known identifiers, so a 200-char pass-through is safe and
     * mirrors the TRANSLATE-stage precedent in ChatWorkflowEngine.
     */
    private List<String> correctionHints(BankPlanToolResult.Stage stage, String errorCode,
            String rawMessage) {
        String rootMessage = StringUtils.left(
                StringUtils.defaultIfBlank(StringUtils.normalizeSpace(rawMessage), "无错误详情"),
                200);
        return List.of("failed_layer=" + StringUtils.defaultIfBlank(errorCode, "UNKNOWN"),
                "root_message=" + rootMessage, staticCorrectionHint(stage));
    }

    private String staticCorrectionHint(BankPlanToolResult.Stage stage) {
        return switch (stage) {
            case SQL_SAFETY -> "只修正 BankQueryPlan，不要直接生成或修改物理 SQL";
            case DATABASE_PREPARE -> "检查计划的机构、指标、时间与查询族组合";
            case DATABASE_EXECUTE -> "根据失败阶段重新生成完整 BankQueryPlan";
            default -> "重新生成符合语义目录约束的完整 BankQueryPlan";
        };
    }
}
