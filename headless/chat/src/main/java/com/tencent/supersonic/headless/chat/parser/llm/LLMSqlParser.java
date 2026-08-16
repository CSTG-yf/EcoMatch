package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.SemanticParser;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlError;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlExecutionCoordinator;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;

/**
 * LLMSqlParser uses large language model to understand query semantics and generate S2SQL
 * statements to be executed by the semantic query engine.
 */
@Slf4j
public class LLMSqlParser implements SemanticParser {

    @Override
    public void parse(ChatQueryContext queryCtx) {
        try {
            // 1.determine whether to skip this parser.
            if (!queryCtx.getRequest().getText2SQLType().enableLLM()) {
                return;
            }
            // 2.get dataSetId from queryCtx and chatCtx.
            LLMRequestService requestService = ContextUtils.getBean(LLMRequestService.class);
            Long dataSetId = requestService.getDataSetId(queryCtx);
            if (dataSetId == null) {
                return;
            }
            log.info("try generating query statement for query:{}, dataSetId:{}",
                    SensitiveLogUtils.summarize(queryCtx.getRequest().getQueryText()), dataSetId);

            // 3.invoke LLM service to do parsing.
            tryParse(queryCtx, dataSetId);
        } catch (BankNl2SqlError e) {
            failConstrainedPlan(queryCtx, e);
            log.error("Failed to parse constrained bank query: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e.getMessage()));
            // Also clear any free-SQL / rule candidates produced earlier in the same parse so they
            // cannot be selected after a terminal bank miss. ChatWorkflowEngine rebuilds
            // selectedParses from candidateQueries, so both lists must be emptied.
            if (queryCtx.getParseResp() != null
                    && queryCtx.getParseResp().getSelectedParses() != null) {
                queryCtx.getParseResp().getSelectedParses().clear();
            }
            if (queryCtx.getCandidateQueries() != null) {
                queryCtx.getCandidateQueries().clear();
            }
        } catch (Exception e) {
            log.error("Failed to parse query: type={}, error=[{}]", e.getClass().getSimpleName(),
                    SensitiveLogUtils.summarize(e.getMessage()));
        }
    }

    private void failConstrainedPlan(ChatQueryContext queryCtx, BankNl2SqlError error) {
        ParseResp parseResp = queryCtx.getParseResp();
        if (parseResp == null) {
            return;
        }
        parseResp.setState(ParseResp.ParseState.FAILED);
        parseResp.setErrorMsg(error.toParserErrorMessage());
    }

    /**
     * Returns only deterministic, plan-shape feedback. Raw exception messages can contain opaque
     * provider or implementation detail and must never enter the model repair context.
     */
    static List<String> compilationCorrectionHints(BankPlanCompilationException.Reason reason,
            String previousPlanJson) {
        if (reason == BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION
                && isDirectTimeComparisonPlan(previousPlanJson)) {
            return List.of("当前计划已声明 time.comparison 非 NONE，却填写了 "
                    + "calculation.type=DIRECT：只将 calculation.type 改为 CHANGE；"
                    + "保留已合法的 intent、指标、机构、日期、基期、dimensions、filters、"
                    + "output 和 limit 后，重新输出完整 BankQueryPlan。");
        }
        if (reason == BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION
                && isRankingProvinceAveragePlan(previousPlanJson)) {
            return List.of("当前计划是“全省排名”而不是“全省均值”比较：删除 "
                    + "benchmark/COMPARE/PROVINCE_AVERAGE，令 filters=[]；保留 intent=RANKING "
                    + "和 bank_organization 维度后，重新输出完整 BankQueryPlan。");
        }
        String code = reason == null ? "COMPILATION_FAILED" : reason.name();
        return List.of("编译器拒绝当前计划组合（" + code
                + "）。请根据上一份完整计划重新检查 intent、calculation、filters、dimensions、"
                + "output 的组合后再输出完整 BankQueryPlan。");
    }

    /**
     * Fail-closed for BANK_CONSTRAINED_PLAN: a model response without an approved BankQueryPlan
     * must never contribute free-SQL candidates to dedup/execution. Drop both sqlOutput and
     * sqlRespMap so the retry loop treats this round as no-candidate instead of running model
     * written SQL. Legitimate plans (even when carrying sqlOutput) are left untouched and follow
     * the coordinator compilation path.
     */
    static void dropUnconstrainedSqlWhenPlanMissing(LLMResp llmResp, boolean bankConstrainedPlan) {
        if (bankConstrainedPlan && llmResp != null && llmResp.getBankQueryPlan() == null
                && (llmResp.getSqlOutput() != null || llmResp.getSqlRespMap() != null)) {
            log.info("bank constrained plan mode: drop free-SQL output without plan");
            llmResp.setSqlOutput(null);
            llmResp.setSqlRespMap(null);
        }
    }

    private static boolean isDirectTimeComparisonPlan(String previousPlanJson) {
        if (previousPlanJson == null || previousPlanJson.isBlank()) {
            return false;
        }
        try {
            BankQueryPlan plan = JsonUtil.toObject(previousPlanJson, BankQueryPlan.class);
            return plan != null && plan.getTime() != null && plan.getCalculation() != null
                    && plan.getTime().getComparison() != null
                    && plan.getTime().getComparison() != BankQueryPlan.TimeComparison.NONE
                    && plan.getCalculation().getType()
                            == BankQueryPlan.CalculationType.DIRECT;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isRankingProvinceAveragePlan(String previousPlanJson) {
        if (previousPlanJson == null || previousPlanJson.isBlank()) {
            return false;
        }
        try {
            BankQueryPlan plan = JsonUtil.toObject(previousPlanJson, BankQueryPlan.class);
            return plan != null
                    && plan.getIntent() == com.tencent.supersonic.headless.chat.intent.BankIntentType.RANKING
                    && plan.getFilters() != null && plan.getFilters().stream().anyMatch(filter ->
                            filter != null && "benchmark".equals(filter.getField())
                                    && "COMPARE".equals(filter.getOperator())
                                    && "PROVINCE_AVERAGE".equals(filter.getValue()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void tryParse(ChatQueryContext queryCtx, Long dataSetId) {
        LLMRequestService requestService = ContextUtils.getBean(LLMRequestService.class);
        LLMResponseService responseService = ContextUtils.getBean(LLMResponseService.class);
        int maxRetries = ContextUtils.getBean(LLMParserConfig.class).getRecallMaxRetries();

        LLMReq llmReq = requestService.getLlmReq(queryCtx, dataSetId);
        publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, false);
        boolean bankConstrainedPlan =
                LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN.equals(llmReq.getSqlGenType());
        if (bankConstrainedPlan) {
            maxRetries = Math.max(maxRetries, 3);
        }

        int currentRetry = 1;
        String bankTraceId = UUID.randomUUID().toString();
        String lastToolFailureSignature = null;
        String previousBankPlanJson = null;
        Map<String, LLMSqlResp> sqlRespMap = new HashMap<>();
        Map<String, Object> selectedDiagnostics = Collections.emptyMap();
        ParseResult parseResult = null;
        ParseResp.BankCandidateRejectionState candidateRejectionState = null;
        SqlErrorType candidateValidationErrorType = null;
        ParseResp.BankCandidateCompilerReason candidateCompilerReason = null;
        while (currentRetry <= maxRetries) {
            log.info("currentRetryRound:{}, start runText2SQL", currentRetry);
            try {
                LLMResp llmResp = requestService.runText2SQL(llmReq);
                if (Objects.nonNull(llmResp)) {
                    dropUnconstrainedSqlWhenPlanMissing(llmResp, bankConstrainedPlan);
                    if (bankConstrainedPlan && llmResp.getBankQueryPlan() != null) {
                        previousBankPlanJson = JsonUtil.toString(llmResp.getBankQueryPlan());
                    }
                    Map<String, Object> attemptDiagnostics = new HashMap<>();
                    if (llmResp.getBankRoutingTelemetry() != null) {
                        attemptDiagnostics.put("bankRoutingTelemetry",
                                llmResp.getBankRoutingTelemetry());
                    }
                    if (bankConstrainedPlan && Objects.nonNull(llmResp.getBankQueryPlan())) {
                        BankNl2SqlExecutionCoordinator.ExecutionCandidate candidate =
                                ContextUtils.getBean(BankNl2SqlExecutionCoordinator.class)
                                        .coordinate(llmReq, llmResp);
                        llmResp.setSqlOutput(candidate.getS2sql());
                        llmResp.setSqlRespMap(Map.of(candidate.getS2sql(),
                                LLMSqlResp.builder().sqlWeight(1D).build()));
                        if (llmResp.getBankCandidateDiagnostics() != null) {
                            attemptDiagnostics.putAll(llmResp.getBankCandidateDiagnostics());
                        }
                        candidate.diagnostics().forEach(attemptDiagnostics::putIfAbsent);
                    }
                    // deduplicate the S2SQL result list and build parserInfo
                    LLMResponseService.DeduplicationOutcome deduplicationOutcome = responseService
                            .getDeduplicationSqlRespWithOutcome(currentRetry, llmResp, llmReq);
                    sqlRespMap = deduplicationOutcome.acceptedCandidates();
                    if (bankConstrainedPlan && MapUtils.isEmpty(sqlRespMap)) {
                        candidateRejectionState = deduplicationOutcome
                                .allCandidatesRejectedByValidation()
                                        ? ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED
                                        : ParseResp.BankCandidateRejectionState.NO_CANDIDATE;
                        candidateValidationErrorType = deduplicationOutcome
                                .allCandidatesRejectedByValidation()
                                        ? deduplicationOutcome.validationErrorType() : null;
                        candidateCompilerReason = null;
                    }
                    if (MapUtils.isNotEmpty(sqlRespMap)) {
                        parseResult = ParseResult.builder().dataSetId(dataSetId).llmReq(llmReq)
                                .llmResp(llmResp).build();
                        selectedDiagnostics = attemptDiagnostics;
                        break;
                    }
                } else if (bankConstrainedPlan) {
                    candidateRejectionState = ParseResp.BankCandidateRejectionState.NO_RESPONSE;
                    candidateValidationErrorType = null;
                    candidateCompilerReason = null;
                }
            } catch (Exception e) {
                log.error("currentRetryRound:{}, runText2SQL failed: type={}, error=[{}]",
                        currentRetry, e.getClass().getSimpleName(),
                        SensitiveLogUtils.summarize(e.getMessage()));
                if (bankConstrainedPlan) {
                    candidateRejectionState = bankCandidateRejectionState(e);
                    candidateValidationErrorType = null;
                    candidateCompilerReason = bankCandidateCompilerReason(e);
                }
                BankPlanCompilationException compilationException =
                        bankPlanCompilationException(e);
                if (bankConstrainedPlan && compilationException != null) {
                    String errorCode = compilationException.getReason() == null
                            ? "COMPILATION_FAILED" : compilationException.getReason().name();
                    String signature = BankPlanToolResult.Stage.COMPILE.name() + ":" + errorCode;
                    if (currentRetry < maxRetries
                            && !signature.equals(lastToolFailureSignature)) {
                        llmReq.setBankPlanToolResult(BankPlanToolResult.failed(currentRetry,
                                bankTraceId, null, BankPlanToolResult.Stage.COMPILE, errorCode,
                                Map.of(), compilationCorrectionHints(compilationException.getReason(),
                                        previousBankPlanJson)));
                        llmReq.setPreviousBankQueryPlanJson(previousBankPlanJson);
                        lastToolFailureSignature = signature;
                    } else {
                        publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, false,
                                candidateRejectionState, candidateValidationErrorType,
                                candidateCompilerReason);
                        throw BankNl2SqlError.compilationFailure(e);
                    }
                } else if (bankConstrainedPlan && !BankNl2SqlError.allowsParserRetry(e)) {
                    publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, false,
                            candidateRejectionState, candidateValidationErrorType,
                            candidateCompilerReason);
                    if (e instanceof BankNl2SqlError bankError) {
                        throw bankError;
                    }
                    if (bankPlanCompilationException(e) != null) {
                        throw BankNl2SqlError.compilationFailure(e);
                    }
                    throw e;
                }
            }
            SqlGenStrategy strategy = SqlGenStrategyFactory.get(llmReq.getSqlGenType());
            ChatApp chatApp = strategy == null || llmReq.getChatAppConfig() == null ? null
                    : llmReq.getChatAppConfig().get(strategy.getAppKey());
            if (chatApp != null && chatApp.getChatModelConfig() != null) {
                ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();
                Double temperature = chatModelConfig.getTemperature();
                if (temperature == 0) {
                    // 报错时增加随机性，减少无效重试
                    chatModelConfig.setTemperature(0.5);
                }
            }
            currentRetry++;
        }
        if (MapUtils.isEmpty(sqlRespMap)) {
            if (bankConstrainedPlan) {
                publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, false,
                        candidateRejectionState == null
                                ? ParseResp.BankCandidateRejectionState.NO_CANDIDATE
                                : candidateRejectionState, candidateValidationErrorType,
                        candidateCompilerReason);
                // Fail closed: never leave an empty bank attempt for rule/free SQL parsers to
                // silently replace with unconstrained S2SQL on the same request.
                throw BankNl2SqlError.noCandidate(candidateRejectionState, candidateCompilerReason);
            }
            return;
        }
        for (Entry<String, LLMSqlResp> entry : sqlRespMap.entrySet()) {
            String sql = entry.getKey();
            double sqlWeight = entry.getValue().getSqlWeight();
            responseService.addParseInfo(queryCtx, parseResult, sql, sqlWeight,
                    selectedDiagnostics);
            publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, true, null,
                    null);
        }
    }

    static void publishBankRoutingAttemptTelemetry(ParseResp parseResp, LLMReq llmReq,
            boolean llmCandidateCreated) {
        publishBankRoutingAttemptTelemetry(parseResp, llmReq, llmCandidateCreated, null, null);
    }

    static void publishBankRoutingAttemptTelemetry(ParseResp parseResp, LLMReq llmReq,
            boolean llmCandidateCreated,
            ParseResp.BankCandidateRejectionState candidateRejectionState,
            SqlErrorType candidateValidationErrorType) {
        publishBankRoutingAttemptTelemetry(parseResp, llmReq, llmCandidateCreated,
                candidateRejectionState, candidateValidationErrorType, null);
    }

    static void publishBankRoutingAttemptTelemetry(ParseResp parseResp, LLMReq llmReq,
            boolean llmCandidateCreated,
            ParseResp.BankCandidateRejectionState candidateRejectionState,
            SqlErrorType candidateValidationErrorType,
            ParseResp.BankCandidateCompilerReason candidateCompilerReason) {
        if (parseResp == null || llmReq == null || llmReq.getSqlGenType() == null) {
            return;
        }
        Map<String, Object> routingTelemetry = llmReq.getBankRoutingTelemetry();
        if (routingTelemetry == null) {
            return;
        }
        Object bankConstrainedPlanEnabled = routingTelemetry.get("bankConstrainedPlanEnabled");
        Object bankDatasetQualified = routingTelemetry.get("bankDatasetQualified");
        if (!(bankConstrainedPlanEnabled instanceof Boolean enabled)
                || !(bankDatasetQualified instanceof Boolean qualified)) {
            return;
        }
        parseResp.setBankRoutingAttemptTelemetry(new ParseResp.BankRoutingAttemptTelemetry(
                enabled, qualified, bankRoutingSqlGenType(llmReq.getSqlGenType()),
                llmCandidateCreated, candidateRejectionState, candidateValidationErrorType,
                candidateCompilerReason));
    }

    static ParseResp.BankCandidateRejectionState bankCandidateRejectionState(
            Exception error) {
        if (error instanceof BankNl2SqlError bankError) {
            if (bankError.getStage() == BankNl2SqlError.Stage.COMPILATION
                    || bankPlanCompilationException(error) != null) {
                return ParseResp.BankCandidateRejectionState.COMPILER_EXCEPTION;
            }
            return ParseResp.BankCandidateRejectionState.PLAN_EXCEPTION;
        }
        if (bankPlanCompilationException(error) != null) {
            return ParseResp.BankCandidateRejectionState.COMPILER_EXCEPTION;
        }
        return ParseResp.BankCandidateRejectionState.NO_CANDIDATE;
    }

    static ParseResp.BankCandidateCompilerReason bankCandidateCompilerReason(Throwable error) {
        BankPlanCompilationException compilationException = bankPlanCompilationException(error);
        if (compilationException == null || compilationException.getReason() == null) {
            return null;
        }
        try {
            return ParseResp.BankCandidateCompilerReason
                    .valueOf(compilationException.getReason().name());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static BankPlanCompilationException bankPlanCompilationException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof BankPlanCompilationException compilationException) {
                return compilationException;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    private static ParseResp.BankRoutingSqlGenType bankRoutingSqlGenType(
            LLMReq.SqlGenType sqlGenType) {
        return switch (sqlGenType) {
            case ONE_PASS_SELF_CONSISTENCY ->
                    ParseResp.BankRoutingSqlGenType.ONE_PASS_SELF_CONSISTENCY;
            case BANK_CONSTRAINED_PLAN -> ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN;
        };
    }
}
