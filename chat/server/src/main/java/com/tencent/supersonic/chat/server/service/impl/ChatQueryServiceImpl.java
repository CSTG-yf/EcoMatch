package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.executor.ChatQueryExecutor;
import com.tencent.supersonic.chat.server.parser.ChatQueryParser;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.chat.server.processor.execute.DataInterpretProcessor;
import com.tencent.supersonic.chat.server.processor.execute.ExecuteResultProcessor;
import com.tencent.supersonic.chat.server.processor.parse.ParseResultProcessor;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.service.ChatQueryService;
import com.tencent.supersonic.chat.server.util.ComponentFactory;
import com.tencent.supersonic.chat.server.util.QueryReqConverter;
import com.tencent.supersonic.common.jsqlparser.FieldExpression;
import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlRemoveHelper;
import com.tencent.supersonic.common.jsqlparser.SqlReplaceHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.DimensionValueReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.api.pojo.response.SearchResult;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.SemanticQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.core.gateway.QueryPerformanceMonitor;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatQueryServiceImpl implements ChatQueryService {

    @Autowired
    private ChatManageService chatManageService;
    @Autowired
    private ChatLayerService chatLayerService;
    @Autowired
    private SemanticLayerService semanticLayerService;
    @Autowired
    @Lazy
    private AgentService agentService;
    @Autowired
    private AuditEventPublisher auditEventPublisher;

    private final List<ChatQueryParser> chatQueryParsers = ComponentFactory.getChatParsers();
    private final List<ChatQueryExecutor> chatQueryExecutors = ComponentFactory.getChatExecutors();
    private final List<ParseResultProcessor> parseResultProcessors =
            ComponentFactory.getParseProcessors();
    private final List<ExecuteResultProcessor> executeResultProcessors =
            ComponentFactory.getExecuteProcessors();

    @Override
    public List<SearchResult> search(ChatParseReq chatParseReq) {
        ParseContext parseContext = buildParseContext(chatParseReq, null);
        Agent agent = parseContext.getAgent();
        if (!agent.enableSearch()) {
            return Lists.newArrayList();
        }
        QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
        return chatLayerService.retrieve(queryNLReq);
    }

    @Override
    public ChatParseResp parse(ChatParseReq chatParseReq) {
        long start = System.nanoTime();
        ChatQueryDO storedQuery = null;
        try {
            Long queryId = chatParseReq.getQueryId();
            Agent agent;
            if (Objects.isNull(queryId)) {
                agent = getAuthorizedAgent(chatParseReq.getAgentId(), chatParseReq.getUser());
                queryId = chatManageService.createChatQuery(chatParseReq);
                chatParseReq.setQueryId(queryId);
                storedQuery = requireStoredQuery(queryId);
                bindStoredQuery(chatParseReq, storedQuery);
            } else {
                chatManageService.checkQueryAccess(queryId, chatParseReq.getUser());
                storedQuery = requireAuthorizedStoredQuery(queryId, chatParseReq.getUser());
                bindStoredQuery(chatParseReq, storedQuery);
                agent = getAuthorizedAgent(storedQuery.getAgentId(), chatParseReq.getUser());
            }

            ParseContext parseContext = new ParseContext(chatParseReq, new ChatParseResp(queryId));
            parseContext.setAgent(agent);
            for (ChatQueryParser parser : chatQueryParsers) {
                if (parser.accept(parseContext)) {
                    parser.parse(parseContext);
                }
            }

            for (ParseResultProcessor processor : parseResultProcessors) {
                if (processor.accept(parseContext)) {
                    processor.process(parseContext);
                }
            }

            if (!parseContext.needFeedback()) {
                parseContext.getResponse().getParseTimeCost()
                        .setParseTime(System.currentTimeMillis() - parseContext.getResponse()
                                .getParseTimeCost().getParseStartTime());
                chatManageService.batchAddParse(chatParseReq, parseContext.getResponse());
                chatManageService.updateParseCostTime(parseContext.getResponse());
            }

            ChatParseResp response = parseContext.getResponse();
            boolean succeeded = isParseSuccessful(response);
            publishChatAudit(chatParseReq.getUser(), storedQuery.getChatId(),
                    storedQuery.getQuestionId(), storedQuery.getQueryText(),
                    succeeded ? AuditEventType.CHAT_PARSE_SUCCEEDED
                            : AuditEventType.CHAT_PARSE_FAILED,
                    succeeded ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    succeeded ? "CHAT_PARSE_COMPLETED" : parseFailureReason(response));
            return response;
        } catch (RuntimeException e) {
            publishChatAudit(chatParseReq.getUser(), storedQuery == null ? null : storedQuery.getChatId(),
                    storedQuery == null ? chatParseReq.getQueryId() : storedQuery.getQuestionId(),
                    storedQuery == null ? null : storedQuery.getQueryText(),
                    AuditEventType.CHAT_PARSE_FAILED, AuditOutcome.FAILURE,
                    "CHAT_PARSE_FAILED");
            throw e;
        } finally {
            QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.PARSE,
                    System.nanoTime() - start);
        }
    }

    @Override
    public QueryResult execute(ChatExecuteReq chatExecuteReq) {
        ChatQueryDO storedQuery = null;
        try {
            chatManageService.checkQueryAccess(chatExecuteReq.getQueryId(),
                    chatExecuteReq.getUser());
            storedQuery = requireAuthorizedStoredQuery(chatExecuteReq.getQueryId(),
                    chatExecuteReq.getUser());
            bindStoredQuery(chatExecuteReq, storedQuery);
            QueryResult queryResult = new QueryResult();
            ExecuteContext executeContext = buildExecuteContext(chatExecuteReq, storedQuery);
            for (ChatQueryExecutor chatQueryExecutor : chatQueryExecutors) {
                if (chatQueryExecutor.accept(executeContext)) {
                    queryResult = chatQueryExecutor.execute(executeContext);
                    if (queryResult != null) {
                        break;
                    }
                }
            }

            executeContext.setResponse(queryResult);
            if (queryResult != null) {
                for (ExecuteResultProcessor processor : executeResultProcessors) {
                    if (processor.accept(executeContext)) {
                        processor.process(executeContext);
                    }
                }
                saveQueryResult(chatExecuteReq, queryResult);
            }
            boolean succeeded = isExecutionSuccessful(queryResult);
            publishChatAudit(chatExecuteReq.getUser(), storedQuery.getChatId(),
                    storedQuery.getQuestionId(), storedQuery.getQueryText(),
                    succeeded ? AuditEventType.CHAT_EXECUTE_SUCCEEDED
                            : AuditEventType.CHAT_EXECUTE_FAILED,
                    succeeded ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    succeeded ? "CHAT_EXECUTE_COMPLETED" : executionFailureReason(queryResult));
            return queryResult;
        } catch (RuntimeException e) {
            publishChatAudit(chatExecuteReq.getUser(), storedQuery == null ? null : storedQuery.getChatId(),
                    storedQuery == null ? chatExecuteReq.getQueryId() : storedQuery.getQuestionId(),
                    storedQuery == null ? null : storedQuery.getQueryText(),
                    AuditEventType.CHAT_EXECUTE_FAILED, AuditOutcome.FAILURE,
                    "CHAT_EXECUTE_FAILED");
            throw e;
        }
    }

    @Override
    public QueryResult getTextSummary(ChatExecuteReq chatExecuteReq) {
        chatManageService.checkQueryAccess(chatExecuteReq.getQueryId(), chatExecuteReq.getUser());
        String text = DataInterpretProcessor.getTextSummary(chatExecuteReq.getQueryId());
        if (StringUtils.isNotBlank(text)) {
            QueryResult res = new QueryResult();
            res.setTextSummary(text);
            res.setQueryId(chatExecuteReq.getQueryId());
            return res;
        } else {
            ChatQueryDO chatQueryDo = chatManageService.getChatQueryDO(chatExecuteReq.getQueryId());
            QueryResult res = JSON.parseObject(chatQueryDo.getQueryResult(), QueryResult.class);
            return res;
        }
    }

    @Override
    public QueryResult parseAndExecute(ChatParseReq chatParseReq) {
        ChatParseResp parseResp = parse(chatParseReq);
        if (CollectionUtils.isEmpty(parseResp.getSelectedParses())) {
            log.debug("chatId:{}, agentId:{}, query:[{}], selected parses are empty",
                    chatParseReq.getChatId(), chatParseReq.getAgentId(),
                    SensitiveLogUtils.summarize(chatParseReq.getQueryText()));
            return null;
        }
        ChatExecuteReq executeReq = new ChatExecuteReq();
        executeReq.setQueryId(parseResp.getQueryId());
        executeReq.setParseId(parseResp.getSelectedParses().get(0).getId());
        executeReq.setQueryText(chatParseReq.getQueryText());
        executeReq.setChatId(chatParseReq.getChatId());
        executeReq.setUser(chatParseReq.getUser());
        executeReq.setAgentId(chatParseReq.getAgentId());
        executeReq.setSaveAnswer(true);
        return execute(executeReq);
    }

    private ParseContext buildParseContext(ChatParseReq chatParseReq, ChatParseResp chatParseResp) {
        ParseContext parseContext = new ParseContext(chatParseReq, chatParseResp);
        Agent agent = getAuthorizedAgent(chatParseReq.getAgentId(), chatParseReq.getUser());
        parseContext.setAgent(agent);
        return parseContext;
    }

    private ExecuteContext buildExecuteContext(ChatExecuteReq chatExecuteReq,
            ChatQueryDO storedQuery) {
        ExecuteContext executeContext = new ExecuteContext(chatExecuteReq);
        SemanticParseInfo parseInfo = chatManageService.getParseInfo(chatExecuteReq.getQueryId(),
                chatExecuteReq.getParseId());
        Agent agent = getAuthorizedAgent(storedQuery.getAgentId(), chatExecuteReq.getUser());
        executeContext.setAgent(agent);
        executeContext.setParseInfo(parseInfo);
        return executeContext;
    }

    private ChatQueryDO requireStoredQuery(Long queryId) {
        ChatQueryDO storedQuery = chatManageService.getChatQueryDO(queryId);
        if (storedQuery == null) {
            throw new IllegalStateException("Authorized query no longer exists: " + queryId);
        }
        return storedQuery;
    }

    ChatQueryDO requireAuthorizedStoredQuery(Long queryId, User user) {
        ChatQueryDO storedQuery = requireStoredQuery(queryId);
        chatManageService.checkChatAccess(storedQuery.getChatId(), user);
        return storedQuery;
    }

    void bindStoredQuery(ChatExecuteReq request, ChatQueryDO storedQuery) {
        request.setQueryId(storedQuery.getQuestionId());
        request.setChatId(toRequestChatId(storedQuery.getChatId()));
        request.setAgentId(storedQuery.getAgentId());
        request.setQueryText(storedQuery.getQueryText());
    }

    void bindStoredQuery(ChatParseReq request, ChatQueryDO storedQuery) {
        request.setQueryId(storedQuery.getQuestionId());
        request.setChatId(toRequestChatId(storedQuery.getChatId()));
        request.setAgentId(storedQuery.getAgentId());
        request.setQueryText(storedQuery.getQueryText());
    }

    private Integer toRequestChatId(Long chatId) {
        return chatId == null ? null : Math.toIntExact(chatId);
    }

    @Override
    public Object queryData(ChatQueryDataReq chatQueryDataReq, User user) throws Exception {
        chatManageService.checkQueryAccess(chatQueryDataReq.getQueryId(), user);
        Integer parseId = chatQueryDataReq.getParseId();
        SemanticParseInfo parseInfo =
                chatManageService.getParseInfo(chatQueryDataReq.getQueryId(), parseId);
        mergeParseInfo(parseInfo, chatQueryDataReq);
        DataSetSchema dataSetSchema =
                semanticLayerService.getDataSetSchema(parseInfo.getDataSetId());

        SemanticQuery semanticQuery = QueryManager.createQuery(parseInfo.getQueryMode());
        semanticQuery.setParseInfo(parseInfo);

        if (LLMSqlQuery.QUERY_MODE.equalsIgnoreCase(parseInfo.getQueryMode())) {
            handleLLMQueryMode(chatQueryDataReq, semanticQuery, dataSetSchema, user);
        } else {
            handleRuleQueryMode(semanticQuery, dataSetSchema, user);
        }

        return executeQuery(semanticQuery, user);
    }

    private void publishChatAudit(User user, Long chatId, Long queryId, String question,
            AuditEventType eventType, AuditOutcome outcome, String reasonCode) {
        auditEventPublisher.publishBestEffort(
                AuditEvent.builder().eventType(eventType).outcome(outcome).reasonCode(reasonCode)
                        .chatId(chatId).queryId(queryId).resourceType("CHAT_QUERY")
                        .resourceId(queryId == null ? null : String.valueOf(queryId))
                        .rawQuestion(question).metadata(Map.of("stage", "CHAT")).build(),
                user);
    }

    static boolean isParseSuccessful(ChatParseResp response) {
        return response != null && ParseResp.ParseState.COMPLETED.equals(response.getState());
    }

    static boolean isExecutionSuccessful(QueryResult result) {
        return result != null && QueryState.SUCCESS.equals(result.getQueryState());
    }

    private String parseFailureReason(ChatParseResp response) {
        return "PARSE_STATE_"
                + (response == null || response.getState() == null ? "UNKNOWN"
                        : response.getState().name());
    }

    private String executionFailureReason(QueryResult result) {
        if (result == null) {
            return "NO_EXECUTOR_RESULT";
        }
        return "QUERY_STATE_"
                + (result.getQueryState() == null ? "UNKNOWN" : result.getQueryState().name());
    }

    private List<String> getFieldsFromSql(SemanticParseInfo parseInfo) {
        SqlInfo sqlInfo = parseInfo.getSqlInfo();
        if (Objects.isNull(sqlInfo) || StringUtils.isNotBlank(sqlInfo.getCorrectedS2SQL())) {
            return new ArrayList<>();
        }
        return SqlSelectHelper.getAllSelectFields(sqlInfo.getCorrectedS2SQL());
    }

    private void handleLLMQueryMode(ChatQueryDataReq chatQueryDataReq, SemanticQuery semanticQuery,
            DataSetSchema dataSetSchema, User user) throws Exception {
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        String rebuiltS2SQL;
        if (checkMetricReplace(chatQueryDataReq, parseInfo)) {
            log.info("rebuild S2SQL with adjusted metrics!");
            SchemaElement metricToReplace = chatQueryDataReq.getMetrics().iterator().next();
            rebuiltS2SQL = replaceMetrics(parseInfo, metricToReplace);
        } else {
            log.info("rebuild S2SQL with adjusted filters!");
            rebuiltS2SQL = replaceFilters(chatQueryDataReq, parseInfo, dataSetSchema);
        }
        // reset SqlInfo and request re-translation
        parseInfo.getSqlInfo().setCorrectedS2SQL(rebuiltS2SQL);
        parseInfo.getSqlInfo().setParsedS2SQL(rebuiltS2SQL);
        parseInfo.getSqlInfo().setQuerySQL(null);
        SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
        SemanticTranslateResp explain = semanticLayerService.translate(semanticQueryReq, user);
        parseInfo.getSqlInfo().setQuerySQL(explain.getQuerySQL());
    }

    private void handleRuleQueryMode(SemanticQuery semanticQuery, DataSetSchema dataSetSchema,
            User user) {
        log.info("rule begin replace metrics and revise filters!");
        validFilter(semanticQuery.getParseInfo().getDimensionFilters());
        validFilter(semanticQuery.getParseInfo().getMetricFilters());
        semanticQuery.buildS2Sql(dataSetSchema);
    }

    private QueryResult executeQuery(SemanticQuery semanticQuery, User user) throws Exception {
        SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        QueryResult queryResult = doExecution(semanticQueryReq, parseInfo.getQueryMode(), user);
        queryResult.setChatContext(semanticQuery.getParseInfo());
        parseInfo.getSqlInfo().setQuerySQL(queryResult.getQuerySql());
        return queryResult;
    }

    private boolean checkMetricReplace(ChatQueryDataReq chatQueryDataReq,
            SemanticParseInfo parseInfo) {
        List<String> oriFields = getFieldsFromSql(parseInfo);
        Set<SchemaElement> metrics = chatQueryDataReq.getMetrics();
        if (CollectionUtils.isEmpty(oriFields) || CollectionUtils.isEmpty(metrics)) {
            return false;
        }
        List<String> metricNames =
                metrics.stream().map(SchemaElement::getName).collect(Collectors.toList());
        return !oriFields.containsAll(metricNames);
    }

    private String replaceFilters(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema) {
        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.debug("Filter replacement input SQL [{}]", SensitiveLogUtils.summarize(correctorSql));
        // get where filter and having filter
        List<FieldExpression> whereExpressionList =
                SqlSelectHelper.getWhereExpressions(correctorSql);

        // replace where filter
        List<Expression> addWhereConditions = new ArrayList<>();
        Set<String> removeWhereFieldNames =
                updateFilters(whereExpressionList, queryData.getDimensionFilters(),
                        parseInfo.getDimensionFilters(), addWhereConditions);

        Map<String, Map<String, String>> filedNameToValueMap = new HashMap<>();
        Set<String> removeDataFieldNames = updateDateInfo(queryData, parseInfo, dataSetSchema,
                filedNameToValueMap, whereExpressionList, addWhereConditions);
        removeWhereFieldNames.addAll(removeDataFieldNames);

        correctorSql = SqlReplaceHelper.replaceValue(correctorSql, filedNameToValueMap);
        correctorSql = SqlRemoveHelper.removeWhereCondition(correctorSql, removeWhereFieldNames);

        // replace having filter
        List<FieldExpression> havingExpressionList =
                SqlSelectHelper.getHavingExpressions(correctorSql);
        List<Expression> addHavingConditions = new ArrayList<>();
        Set<String> removeHavingFieldNames =
                updateFilters(havingExpressionList, queryData.getDimensionFilters(),
                        parseInfo.getDimensionFilters(), addHavingConditions);
        correctorSql = SqlReplaceHelper.replaceHavingValue(correctorSql, new HashMap<>());
        correctorSql = SqlRemoveHelper.removeHavingCondition(correctorSql, removeHavingFieldNames);

        correctorSql = SqlAddHelper.addWhere(correctorSql, addWhereConditions);
        correctorSql = SqlAddHelper.addHaving(correctorSql, addHavingConditions);
        log.debug("Filter replacement output SQL [{}]", SensitiveLogUtils.summarize(correctorSql));
        return correctorSql;
    }

    private String replaceMetrics(SemanticParseInfo parseInfo, SchemaElement metric) {
        List<String> oriMetrics = parseInfo.getMetrics().stream().map(SchemaElement::getName)
                .collect(Collectors.toList());
        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.debug("Metric replacement input SQL [{}]", SensitiveLogUtils.summarize(correctorSql));
        log.info("filteredMetrics:{},metrics:{}", oriMetrics, metric);
        Map<String, Pair<String, String>> fieldMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(oriMetrics) && !oriMetrics.contains(metric.getName())) {
            fieldMap.put(oriMetrics.get(0), Pair.of(metric.getName(), metric.getDefaultAgg()));
            correctorSql = SqlReplaceHelper.replaceAggFields(correctorSql, fieldMap);
        }
        log.debug("Metric replacement output SQL [{}]", SensitiveLogUtils.summarize(correctorSql));
        return correctorSql;
    }

    private QueryResult doExecution(SemanticQueryReq semanticQueryReq, String queryMode, User user)
            throws Exception {
        SemanticQueryResp queryResp = semanticLayerService.queryByReq(semanticQueryReq, user);
        QueryResult queryResult = new QueryResult();

        if (queryResp != null) {
            queryResult.setQueryAuthorization(queryResp.getQueryAuthorization());
            queryResult.setQuerySql(queryResp.getSql());
            queryResult.setQueryResults(queryResp.getResultList());
            queryResult.setQueryColumns(queryResp.getColumns());
            queryResult.setDataMasked(queryResp.isDataMasked());
            queryResult.setMaskedColumns(queryResp.getMaskedColumns());
        } else {
            queryResult.setQueryResults(new ArrayList<>());
            queryResult.setQueryColumns(new ArrayList<>());
        }

        queryResult.setQueryMode(queryMode);
        queryResult.setQueryState(QueryState.SUCCESS);
        return queryResult;
    }

    private Set<String> updateDateInfo(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema, Map<String, Map<String, String>> filedNameToValueMap,
            List<FieldExpression> fieldExpressionList, List<Expression> addConditions) {
        Set<String> removeFieldNames = new HashSet<>();
        if (Objects.isNull(queryData.getDateInfo())) {
            return removeFieldNames;
        }
        if (queryData.getDateInfo().getUnit() > 1) {
            queryData.getDateInfo()
                    .setStartDate(DateUtils.getBeforeDate(queryData.getDateInfo().getUnit() + 1));
            queryData.getDateInfo().setEndDate(DateUtils.getBeforeDate(0));
        }
        SchemaElement partitionDimension = dataSetSchema.getPartitionDimension();
        // startDate equals to endDate
        for (FieldExpression fieldExpression : fieldExpressionList) {
            if (partitionDimension.getName().equals(fieldExpression.getFieldName())) {
                // first remove,then add
                removeFieldNames.add(partitionDimension.getName());
                GreaterThanEquals greaterThanEquals = new GreaterThanEquals();
                addTimeFilters(queryData.getDateInfo().getStartDate(), greaterThanEquals,
                        addConditions, partitionDimension);
                MinorThanEquals minorThanEquals = new MinorThanEquals();
                addTimeFilters(queryData.getDateInfo().getEndDate(), minorThanEquals, addConditions,
                        partitionDimension);
                break;
            }
        }
        for (FieldExpression fieldExpression : fieldExpressionList) {
            for (QueryFilter queryFilter : queryData.getDimensionFilters()) {
                if (queryFilter.getOperator().equals(FilterOperatorEnum.LIKE)
                        && FilterOperatorEnum.LIKE.getValue()
                                .equalsIgnoreCase(fieldExpression.getOperator())) {
                    Map<String, String> replaceMap = new HashMap<>();
                    String preValue = fieldExpression.getFieldValue().toString();
                    String curValue = queryFilter.getValue().toString();
                    if (preValue.startsWith("%")) {
                        curValue = "%" + curValue;
                    }
                    if (preValue.endsWith("%")) {
                        curValue = curValue + "%";
                    }
                    replaceMap.put(preValue, curValue);
                    filedNameToValueMap.put(fieldExpression.getFieldName(), replaceMap);
                    break;
                }
            }
        }
        parseInfo.setDateInfo(queryData.getDateInfo());
        return removeFieldNames;
    }

    private <T extends ComparisonOperator> void addTimeFilters(String date, T comparisonExpression,
            List<Expression> addConditions, SchemaElement partitionDimension) {
        Column column = new Column(partitionDimension.getName());
        StringValue stringValue = new StringValue(date);
        comparisonExpression.setLeftExpression(column);
        comparisonExpression.setRightExpression(stringValue);
        addConditions.add(comparisonExpression);
    }

    private Set<String> updateFilters(List<FieldExpression> fieldExpressionList,
            Set<QueryFilter> metricFilters, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        Set<String> removeFieldNames = new HashSet<>();
        if (CollectionUtils.isEmpty(metricFilters)) {
            return removeFieldNames;
        }

        for (QueryFilter dslQueryFilter : metricFilters) {
            for (FieldExpression fieldExpression : fieldExpressionList) {
                if (fieldExpression.getFieldName() != null
                        && fieldExpression.getFieldName().contains(dslQueryFilter.getName())) {
                    removeFieldNames.add(dslQueryFilter.getName());
                    handleFilter(dslQueryFilter, contextMetricFilters, addConditions);
                    break;
                }
            }
        }
        return removeFieldNames;
    }

    private void handleFilter(QueryFilter dslQueryFilter, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        FilterOperatorEnum operator = dslQueryFilter.getOperator();

        if (operator == FilterOperatorEnum.IN) {
            addWhereInFilters(dslQueryFilter, new InExpression(), contextMetricFilters,
                    addConditions);
        } else {
            ComparisonOperator expression = FilterOperatorEnum.createExpression(operator);
            if (Objects.nonNull(expression)) {
                addWhereFilters(dslQueryFilter, expression, contextMetricFilters, addConditions);
            }
        }
    }

    // add in condition to sql where condition
    private void addWhereInFilters(QueryFilter dslQueryFilter, InExpression inExpression,
            Set<QueryFilter> contextMetricFilters, List<Expression> addConditions) {
        Column column = new Column(dslQueryFilter.getName());
        ParenthesedExpressionList parenthesedExpressionList = new ParenthesedExpressionList<>();
        List<String> valueList =
                JsonUtil.toList(JsonUtil.toString(dslQueryFilter.getValue()), String.class);
        if (CollectionUtils.isEmpty(valueList)) {
            return;
        }
        valueList.forEach(o -> {
            StringValue stringValue = new StringValue(o);
            parenthesedExpressionList.add(stringValue);
        });
        inExpression.setLeftExpression(column);
        inExpression.setRightExpression(parenthesedExpressionList);
        addConditions.add(inExpression);
        contextMetricFilters.forEach(o -> {
            if (o.getName().equals(dslQueryFilter.getName())) {
                o.setValue(dslQueryFilter.getValue());
                o.setOperator(dslQueryFilter.getOperator());
            }
        });
    }

    // add where filter
    private void addWhereFilters(QueryFilter dslQueryFilter,
            ComparisonOperator comparisonExpression, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        String columnName = dslQueryFilter.getName();
        if (StringUtils.isNotBlank(dslQueryFilter.getFunction())) {
            columnName = dslQueryFilter.getFunction() + "(" + dslQueryFilter.getName() + ")";
        }
        if (Objects.isNull(dslQueryFilter.getValue())) {
            return;
        }
        Column column = new Column(columnName);
        comparisonExpression.setLeftExpression(column);
        if (StringUtils.isNumeric(dslQueryFilter.getValue().toString())) {
            LongValue longValue =
                    new LongValue(Long.parseLong(dslQueryFilter.getValue().toString()));
            comparisonExpression.setRightExpression(longValue);
        } else {
            StringValue stringValue = new StringValue(dslQueryFilter.getValue().toString());
            comparisonExpression.setRightExpression(stringValue);
        }
        addConditions.add(comparisonExpression);
        contextMetricFilters.forEach(o -> {
            if (o.getName().equals(dslQueryFilter.getName())) {
                o.setValue(dslQueryFilter.getValue());
                o.setOperator(dslQueryFilter.getOperator());
            }
        });
    }

    private void mergeParseInfo(SemanticParseInfo parseInfo, ChatQueryDataReq queryData) {
        if (Objects.nonNull(queryData.getDateInfo())) {
            parseInfo.setDateInfo(queryData.getDateInfo());
        }
        if (LLMSqlQuery.QUERY_MODE.equals(parseInfo.getQueryMode())) {
            return;
        }
        if (!CollectionUtils.isEmpty(queryData.getDimensions())) {
            parseInfo.setDimensions(queryData.getDimensions());
        }
        if (!CollectionUtils.isEmpty(queryData.getMetrics())) {
            parseInfo.setMetrics(queryData.getMetrics());
        }
        if (!CollectionUtils.isEmpty(queryData.getDimensionFilters())) {
            parseInfo.setDimensionFilters(queryData.getDimensionFilters());
        }
        if (!CollectionUtils.isEmpty(queryData.getMetricFilters())) {
            parseInfo.setMetricFilters(queryData.getMetricFilters());
        }

        parseInfo.setSqlInfo(new SqlInfo());
    }

    private void validFilter(Set<QueryFilter> filters) {
        Iterator<QueryFilter> iterator = filters.iterator();
        while (iterator.hasNext()) {
            QueryFilter queryFilter = iterator.next();
            Object queryFilterValue = queryFilter.getValue();
            if (Objects.isNull(queryFilterValue)) {
                iterator.remove();
                continue;
            }
            List<String> collection = new ArrayList<>();
            if (queryFilterValue instanceof List) {
                collection.addAll((List) queryFilterValue);
            } else if (queryFilterValue instanceof String) {
                collection.add((String) queryFilterValue);
            }
            if (FilterOperatorEnum.IN.equals(queryFilter.getOperator())
                    && CollectionUtils.isEmpty(collection)) {
                iterator.remove();
            }
        }
    }

    @Override
    public Object queryDimensionValue(DimensionValueReq dimensionValueReq, User user) {
        Integer agentId = dimensionValueReq.getAgentId();
        Agent agent = getAuthorizedAgent(agentId, user);
        dimensionValueReq.setDataSetIds(agent.getDataSetIds());
        return semanticLayerService.queryDimensionValue(dimensionValueReq, user);
    }

    private Agent getAuthorizedAgent(Integer agentId, User user) {
        if (agentId == null || user == null) {
            throw new InvalidPermissionException("Agent access requires an authenticated user");
        }
        return agentService.getAgents(user, AuthType.VIEWER).stream()
                .filter(agent -> Objects.equals(agentId, agent.getId())).findFirst()
                .orElseThrow(() -> new InvalidPermissionException(
                        "No permission to access agent " + agentId));
    }

    public void saveQueryResult(ChatExecuteReq chatExecuteReq, QueryResult queryResult) {
        // The history record only retains the query result of the first parse
        if (chatExecuteReq.getParseId() > 1) {
            return;
        }
        chatManageService.saveQueryResult(chatExecuteReq, queryResult);
    }
}
