package com.tencent.supersonic.headless.server.utils;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.ChatWorkflowState;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.corrector.LLMPhysicalSqlCorrector;
import com.tencent.supersonic.headless.chat.corrector.SemanticCorrector;
import com.tencent.supersonic.headless.chat.mapper.SchemaMapper;
import com.tencent.supersonic.headless.chat.parser.SemanticParser;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankEnvironmentFaultClassifier;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlError;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.SemanticQuery;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatWorkflowEngine {

    private final List<SchemaMapper> schemaMappers = CoreComponentFactory.getSchemaMappers();
    private final List<SemanticParser> semanticParsers = CoreComponentFactory.getSemanticParsers();
    private final List<SemanticCorrector> semanticCorrectors =
            CoreComponentFactory.getSemanticCorrectors();

    public void start(ChatWorkflowState initialState, ChatQueryContext queryCtx) {
        ParseResp parseResult = queryCtx.getParseResp();
        queryCtx.setChatWorkflowState(initialState);
        while (queryCtx.getChatWorkflowState() != ChatWorkflowState.FINISHED) {
            switch (queryCtx.getChatWorkflowState()) {
                case MAPPING:
                    performMapping(queryCtx);
                    if (queryCtx.getMapInfo().isEmpty()) {
                        parseResult.setState(ParseResp.ParseState.FAILED);
                        parseResult.setErrorMsg(
                                "No semantic entities can be mapped against user question.");
                        queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    } else {
                        queryCtx.setChatWorkflowState(ChatWorkflowState.PARSING);
                    }
                    break;
                case PARSING:
                    performParsing(queryCtx);
                    // A terminal bank constrained-plan failure must stay failed: do not let residual
                    // free/rule candidates overwrite the bank error and win the parse.
                    if (ParseResp.ParseState.FAILED.equals(parseResult.getState())
                            && BankNl2SqlError.isTerminalParserError(parseResult.getErrorMsg())) {
                        queryCtx.getCandidateQueries().clear();
                        parseResult.setSelectedParses(new ArrayList<>());
                        queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    } else if (queryCtx.getCandidateQueries().isEmpty()) {
                        parseResult.setState(ParseResp.ParseState.FAILED);
                        parseResult.setErrorMsg("No semantic queries can be parsed out.");
                        queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    } else {
                        List<SemanticParseInfo> parseInfos = queryCtx.getCandidateQueries().stream()
                                .map(SemanticQuery::getParseInfo).collect(Collectors.toList());
                        parseResult.setSelectedParses(parseInfos);
                        if (queryCtx.needSQL()) {
                            queryCtx.setChatWorkflowState(ChatWorkflowState.S2SQL_CORRECTING);
                        } else {
                            parseResult.setState(ParseResp.ParseState.COMPLETED);
                            queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                        }
                    }
                    break;
                case S2SQL_CORRECTING:
                    performCorrecting(queryCtx);
                    queryCtx.setChatWorkflowState(ChatWorkflowState.TRANSLATING);
                    break;
                case TRANSLATING:
                    long start = System.currentTimeMillis();
                    performTranslating(queryCtx, parseResult);
                    parseResult.getParseTimeCost().setSqlTime(System.currentTimeMillis() - start);
                    queryCtx.setChatWorkflowState(ChatWorkflowState.PHYSICAL_SQL_CORRECTING);
                    break;
                case PHYSICAL_SQL_CORRECTING:
                    performPhysicalSqlCorrecting(queryCtx);
                    queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    break;
                default:
                    if (parseResult.getState().equals(ParseResp.ParseState.PENDING)) {
                        parseResult.setState(ParseResp.ParseState.COMPLETED);
                    }
                    queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    break;
            }
        }
    }

    private void performMapping(ChatQueryContext queryCtx) {
        if (Objects.isNull(queryCtx.getMapInfo())
                || MapUtils.isEmpty(queryCtx.getMapInfo().getDataSetElementMatches())) {
            schemaMappers.forEach(mapper -> mapper.map(queryCtx));
        }
    }

    private void performParsing(ChatQueryContext queryCtx) {
        semanticParsers.forEach(parser -> {
            parser.parse(queryCtx);
            log.debug("{} result [{}]", parser.getClass().getSimpleName(),
                    SensitiveLogUtils.summarize(JsonUtil.toString(queryCtx)));
        });
    }

    private void performCorrecting(ChatQueryContext queryCtx) {
        List<SemanticQuery> candidateQueries = queryCtx.getCandidateQueries();
        if (CollectionUtils.isNotEmpty(candidateQueries)) {
            for (SemanticQuery semanticQuery : candidateQueries) {
                for (SemanticCorrector corrector : semanticCorrectors) {
                    corrector.correct(queryCtx, semanticQuery.getParseInfo());
                    if (!ChatWorkflowState.S2SQL_CORRECTING
                            .equals(queryCtx.getChatWorkflowState())) {
                        break;
                    }
                }
            }
        }
    }

    private void performTranslating(ChatQueryContext queryCtx, ParseResp parseResult) {
        List<SemanticParseInfo> semanticParseInfos = queryCtx.getCandidateQueries().stream()
                .map(SemanticQuery::getParseInfo).collect(Collectors.toList());
        List<String> errorMsg = new ArrayList<>();
        if (StringUtils.isNotBlank(parseResult.getErrorMsg())) {
            errorMsg.add(parseResult.getErrorMsg());
        }
        semanticParseInfos.forEach(parseInfo -> {
            try {
                SemanticQuery semanticQuery = QueryManager.createQuery(parseInfo.getQueryMode());
                if (Objects.isNull(semanticQuery)) {
                    return;
                }
                semanticQuery.setParseInfo(parseInfo);
                SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
                SemanticLayerService queryService =
                        ContextUtils.getBean(SemanticLayerService.class);
                SemanticTranslateResp explain =
                        queryService.translate(semanticQueryReq, queryCtx.getRequest().getUser());
                if (explain.isOk()) {
                    parseInfo.getSqlInfo().setQuerySQL(explain.getQuerySQL());
                    parseResult.setState(ParseResp.ParseState.COMPLETED);
                } else {
                    parseResult.setState(ParseResp.ParseState.FAILED);
                    persistBankTranslateFailure(parseInfo, "SEMANTIC_TRANSLATE_REJECTED",
                            explain == null ? null : explain.getErrMsg());
                }
                if (StringUtils.isNotBlank(explain.getErrMsg())) {
                    errorMsg.add(explain.getErrMsg());
                }
                log.info("SqlInfoProcessor result: parsed=[{}], corrected=[{}], physical=[{}]",
                        SensitiveLogUtils.summarize(StringUtils
                                .normalizeSpace(parseInfo.getSqlInfo().getParsedS2SQL())),
                        SensitiveLogUtils.summarize(StringUtils
                                .normalizeSpace(parseInfo.getSqlInfo().getCorrectedS2SQL())),
                        SensitiveLogUtils.summarize(
                                StringUtils.normalizeSpace(parseInfo.getSqlInfo().getQuerySQL())));
            } catch (Exception e) {
                // Surface the real translation message in local bank ablation debugging; still
                // keep the user-facing parse error generic.
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                log.warn(
                        "SQL translation failed: type={}, rootType={}, rootMsg=[{}], topMsg=[{}]",
                        e.getClass().getSimpleName(), root.getClass().getSimpleName(),
                        StringUtils.left(String.valueOf(root.getMessage()), 800),
                        StringUtils.left(String.valueOf(e.getMessage()), 800));
                parseResult.setState(ParseResp.ParseState.FAILED);
                persistBankTranslateFailure(parseInfo, root.getClass().getName(),
                        root.getMessage());
                errorMsg.add("Semantic query translation failed");
            }
        });
        if (!errorMsg.isEmpty()) {
            parseResult.setErrorMsg(String.join("\n", errorMsg));
        }
    }

    /**
     * Translation used to be the only failure stage that vanished without bank tool feedback. For
     * constrained bank plans this persists a FAILED TRANSLATE tool result so the downstream repair
     * loop can regenerate the plan from real facts instead of silently losing the round.
     */
    private void persistBankTranslateFailure(SemanticParseInfo parseInfo, String rootType,
            String rootMessage) {
        Map<String, Object> properties = parseInfo.getProperties();
        if (properties == null
                || !properties.containsKey(BankPlanToolResult.PLAN_PROPERTY_KEY)) {
            return;
        }
        BankPlanToolResult previous =
                BankPlanToolResult.from(properties.get(BankPlanToolResult.PROPERTY_KEY));
        int attempt = previous == null ? 1 : previous.getAttempt() + 1;
        String traceId = previous == null || StringUtils.isBlank(previous.getTraceId())
                ? UUID.randomUUID().toString() : previous.getTraceId();
        String message = StringUtils.left(StringUtils.defaultString(rootMessage), 200);
        List<String> hints = List.of("failed_layer=" + classifyTranslationLayer(rootType, message),
                "root_type=" + StringUtils.defaultString(rootType), "root_message=" + message,
                "根据 failed_layer/root_type 修正计划中的机构、指标、时间或查询族组合后，"
                        + "重新输出完整 BankPlanningResponse。");
        properties.put(BankPlanToolResult.PROPERTY_KEY, BankPlanToolResult.failed(attempt, traceId,
                previous == null ? null : previous.getPreviousPlanFingerprint(),
                BankPlanToolResult.Stage.TRANSLATE, "TRANSLATION_FAILED", Map.of(), hints));
    }

    private String classifyTranslationLayer(String rootType, String message) {
        String normalized = (StringUtils.defaultString(rootType) + " "
                + StringUtils.defaultString(message)).toLowerCase(Locale.ROOT);
        if (BankEnvironmentFaultClassifier.isEnvironmentFault(null, normalized)) {
            return BankEnvironmentFaultClassifier.CODE;
        }
        if (normalized.contains("calcite")) {
            return "CALCITE_VALIDATE";
        }
        if (normalized.contains("jdbc") || normalized.contains("syntax")
                || normalized.contains("column \"") || normalized.contains("sqlgrammar")) {
            return "JDBC_GRAMMAR";
        }
        return "OTHER_TRANSLATION";
    }

    private void performPhysicalSqlCorrecting(ChatQueryContext queryCtx) {
        List<SemanticQuery> candidateQueries = queryCtx.getCandidateQueries();
        if (CollectionUtils.isNotEmpty(candidateQueries)) {
            for (SemanticQuery semanticQuery : candidateQueries) {
                for (SemanticCorrector corrector : semanticCorrectors) {
                    if (corrector instanceof LLMPhysicalSqlCorrector) {
                        corrector.correct(queryCtx, semanticQuery.getParseInfo());
                        // 如果物理SQL被修正了，更新querySQL为修正后的版本
                        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
                        if (StringUtils.isNotBlank(parseInfo.getSqlInfo().getCorrectedQuerySQL())) {
                            parseInfo.getSqlInfo()
                                    .setQuerySQL(parseInfo.getSqlInfo().getCorrectedQuerySQL());
                            log.info("Physical SQL corrected [{}]", SensitiveLogUtils
                                    .summarize(parseInfo.getSqlInfo().getQuerySQL()));
                        }
                        break;
                    }
                }
            }
        }
    }
}
