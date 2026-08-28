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
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankEnvironmentFaultClassifier;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFreeSqlFallbackHook;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlError;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlExecutionCoordinator;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankSemanticRegistry;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
     * Returns only deterministic, plan-shape feedback derived from our own compiler reasons and the
     * registry whitelists. BankPlanCompilationException messages originate in this codebase, so a
     * sanitized single-line copy is safe to surface; arbitrary provider or transport exceptions
     * still never enter the model repair context.
     */
    static List<String> compilationToolFeedback(BankPlanCompilationException exception,
            String previousPlanJson) {
        List<String> hints = new ArrayList<>();
        String compilerMessage = sanitizeCompilerMessage(exception.getMessage());
        if (!compilerMessage.isEmpty()) {
            hints.add("编译器拒绝原因（原文）：" + compilerMessage);
        }
        hints.addAll(compilationCorrectionHints(exception.getReason(), previousPlanJson));
        hints.add("修正后必须重新输出完整 BankQueryPlan；未指出的槽位保持上一份计划原值。");
        return List.copyOf(hints);
    }

    /** Fills allowedValues for the tool_result from registry whitelists keyed by slot name. */
    static Map<String, List<String>> compilationAllowedValues(
            BankPlanCompilationException.Reason reason) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (reason == null) {
            return Map.of();
        }
        switch (reason) {
            case METRIC_UNAVAILABLE -> values.put("metrics[].bizName",
                    sorted(BankSemanticRegistry.metricCodes()));
            case DIMENSION_UNAVAILABLE, ORGANIZATION_DIMENSION_UNAVAILABLE,
                    TIME_DIMENSION_UNAVAILABLE ->
                values.put("dimensions", sorted(BankSemanticRegistry.dimensions()));
            case OUTPUT_ORDER_MISMATCH, ORDER_FIELD_NOT_SELECTED -> {
                values.put("orderBy[].direction", sorted(BankSemanticRegistry.sortDirections()));
                values.put("output.columns", List.of("<已选维度>", "<已选 ZB### 指标>"));
            }
            case UNSUPPORTED_FILTER -> {
                values.put("filterFields", sorted(BankSemanticRegistry.filterFields()));
                values.put("filterOperators", sorted(BankSemanticRegistry.filterOperators()));
            }
            case UNSUPPORTED_CALCULATION -> {
                values.put("calculation.type", sorted(BankSemanticRegistry.calculationTypes()));
                values.put("time.comparison", sorted(BankSemanticRegistry.timeComparisons()));
            }
            case UNSUPPORTED_QUERY_SHAPE -> {
                values.put("intent", sorted(BankSemanticRegistry.intents()));
                values.put("calculation.type", sorted(BankSemanticRegistry.calculationTypes()));
                values.put("time.comparison", sorted(BankSemanticRegistry.timeComparisons()));
            }
            case CLARIFICATION_REQUIRED -> values.put("action",
                    sorted(BankSemanticRegistry.planActions()));
            default -> { /* terminal/config-class reasons carry no slot whitelist */ }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns only deterministic, plan-shape feedback. Raw exception messages can contain opaque
     * provider or implementation detail and must never enter the model repair context.
     */
    static List<String> compilationCorrectionHints(BankPlanCompilationException.Reason reason,
            String previousPlanJson) {
        if (reason == BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION
                && isMonthAndYearComparisonPlan(previousPlanJson)) {
            return List.of("当前计划是同一日期的环比+同比比较：保留 intent=CHANGE、恰好一个指标、"
                    + "恰好一个机构和当前日期；令 time.comparison=MOM_AND_YOY、"
                    + "baselineStartDate=null、baselineEndDate=null、dimensions=[]、filters=[]、"
                    + "calculation.type=CHANGE、orderBy=[]、limit=null 后，重新输出完整 BankQueryPlan。"
                    + "两个基期由编译器确定，不要自行填写。");
        }
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
        if (reason == BankPlanCompilationException.Reason.INVALID_PLAN
                && isIdenticalOperandRatioPlan(previousPlanJson)) {
            return List.of("当前计划的比率分子与分母解析到同一个目录指标（metrics[0] 与 "
                    + "metrics[1]/calculation.baseline 相同），比率会恒为常数：metrics 必须选择"
                    + "两个不同的目录 ZB### 指标，metrics[0]=分子、metrics[1]=calculation."
                    + "baseline=分母；保持其余槽位不变，重新输出完整 BankQueryPlan。");
        }
        return switch (reason == null ? BankPlanCompilationException.Reason.INVALID_PLAN : reason) {
            case INVALID_PLAN -> List.of("计划 JSON 未通过基础合同校验：逐项核对 version/action/"
                    + "intent/metrics/dimensions/organizations/time/filters/calculation/orderBy/"
                    + "limit/output 是否齐全且类型正确（version=\"1.0\"、time 四个日期字段齐全），"
                    + "然后重新输出完整 BankQueryPlan。");
            case CLARIFICATION_REQUIRED -> List.of("编译器判定该计划不可执行：若题干结合权威目录已能"
                    + "唯一确定指标、机构与时间，必须 action=EXECUTE 并补全槽位；仅当确实无法唯一"
                    + "确定时才允许 action=CLARIFY 且 plan=null。");
            case METRIC_UNAVAILABLE -> List.of("metrics 中存在目录外或非法代码：bizName 只能精确"
                    + "填写权威目录的大写 ZB###（见 allowedValues），alias 一律 null；derivedMetrics "
                    + "也只能来自目录派生清单。不得使用中文指标名或自造代码。");
            case DIMENSION_UNAVAILABLE -> List.of("dimensions 含目录外维度：合法值只有 "
                    + "\"bank_organization\" 与 \"bank_data_date\"（见 allowedValues）；其余写法一律非法。");
            case ORGANIZATION_DIMENSION_UNAVAILABLE -> List.of("本查询族需要机构维度：dimensions "
                    + "必须包含 \"bank_organization\"（organizations=[] 的全省族同样要求），不得用机构"
                    + "名称文本列替代。");
            case TIME_DIMENSION_UNAVAILABLE -> List.of("时间序列计划需要日期维度：dimensions 必须"
                    + "包含 \"bank_data_date\" 且 granularity 保持 DAY；不要把日明细压缩成单行汇总。");
            case OUTPUT_ORDER_MISMATCH -> List.of("output.columns 与所选维度/指标不一致：只能先列 "
                    + "dimensions 中已选维度，再按 metrics[].bizName 顺序排列；禁止出现 aggregate_value、"
                    + "rank_position 等编译结果列。");
            case ORDER_FIELD_NOT_SELECTED -> List.of("orderBy[].field 只能是本计划已选的 "
                    + "metrics[].bizName 或 dimensions 中已选维度；含 derivedMetrics 时 orderBy=[] 由"
                    + "编译器排序；direction 见 allowedValues，且按目录 direction 列照抄"
                    + "（HIGHER_BETTER→DESC、LOWER_BETTER→ASC）。");
            case UNSUPPORTED_FILTER -> List.of("filters 存在非法 field/operator/value 组合：field 与 "
                    + "operator 的合法集合见 allowedValues；IN/NOT_IN 必须用 values 列表且 value=null；"
                    + "benchmark 只能 COMPARE/PROVINCE_AVERAGE 且 benchmark 对象本身必须在 filters 中；"
                    + "rank/rank_from_bottom 仅限 RANKING 使用。");
            case UNSUPPORTED_CALCULATION -> List.of("intent×calculation×comparison 组合不被支持："
                    + "DIRECT 只允许 comparison=NONE；CHANGE 要求 comparison 非 NONE 且 calculation.type="
                    + "CHANGE；RATIO 仅限点值比率且 baseline=分母代码；COUNT_DAYS_ABOVE_PROVINCE_AVERAGE "
                    + "必须携带 benchmark 过滤且禁止追加任何其他过滤器。");
            case S2SQL_RENDER_FAILED -> List.of("模板渲染失败通常是切片过滤器或聚合组合越界：rank/"
                    + "rank_from_bottom 只能用于 RANKING；“前N和后N”需要 rank 与 rank_from_bottom 两个"
                    + "过滤器且 limit=2*N；多指标聚合 dimensions 只能是 [\"bank_organization\"]。");
            case UNSUPPORTED_QUERY_SHAPE -> List.of("当前 intent×calculation 组合没有对应的查询族模板"
                    + "（例如“排名”类意图不能直接套用 CHANGE 族）：对照 allowedValues 重新选择合法组合——"
                    + "RANKING/DIRECT 配 rank 过滤、CHANGE/intent=CHANGE 配非 NONE 比较基期、"
                    + "RATIO/intent=RATIO 配两个不同指标；若问题确实需要跨族组合，请保持原意并改写为"
                    + "上述合法形状之一后，重新输出完整 BankQueryPlan。");
            default -> List.of("编译器拒绝当前计划组合（" + reason + "）。请根据上一份完整计划重新检查 "
                    + "intent、calculation、filters、dimensions、output 的组合后，重新输出完整 "
                    + "BankQueryPlan。");
        };
    }

    private static String sanitizeCompilerMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        if (message.contains("可填写值目录") || message.contains("【语义目录】")) {
            return "";
        }
        String flattened = message.replaceAll("\\s+", " ").strip();
        int maxCompilerMessageLength = 200;
        if (flattened.length() > maxCompilerMessageLength) {
            flattened = flattened.substring(0, maxCompilerMessageLength);
        }
        return flattened;
    }

    private static List<String> sorted(java.util.Set<String> values) {
        return values.stream().sorted().collect(java.util.stream.Collectors.toUnmodifiableList());
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

    private static boolean isMonthAndYearComparisonPlan(String previousPlanJson) {
        if (previousPlanJson == null || previousPlanJson.isBlank()) {
            return false;
        }
        try {
            BankQueryPlan plan = JsonUtil.toObject(previousPlanJson, BankQueryPlan.class);
            return plan != null && plan.getTime() != null
                    && plan.getTime().getComparison() == BankQueryPlan.TimeComparison.MOM_AND_YOY;
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

    /** Detects a ratio plan whose two operands resolve to the same catalog metric. */
    private static boolean isIdenticalOperandRatioPlan(String previousPlanJson) {
        if (previousPlanJson == null || previousPlanJson.isBlank()) {
            return false;
        }
        try {
            BankQueryPlan plan = JsonUtil.toObject(previousPlanJson, BankQueryPlan.class);
            if (plan == null || plan.getCalculation() == null
                    || plan.getCalculation().getType() != BankQueryPlan.CalculationType.RATIO
                    || plan.getMetrics() == null || plan.getMetrics().size() < 2
                    || plan.getCalculation().getBaseline() == null) {
                return false;
            }
            String numerator = plan.getMetrics().get(0).getBizName();
            String denominator = plan.getCalculation().getBaseline();
            return numerator != null && numerator.equalsIgnoreCase(denominator);
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
            // One initial parser round plus one execution/tool-repair round. Structured planning
            // already owns its single validation repair and must not be multiplied here.
            maxRetries = 2;
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
                if (bankConstrainedPlan && BankEnvironmentFaultClassifier.isEnvironmentFault(e)) {
                    // Provider outage: no plan repair can help; stop every remaining model round.
                    publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, false,
                            bankCandidateRejectionState(e), null, null);
                    throw BankNl2SqlError.modelFailure(e);
                }
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
                                compilationAllowedValues(compilationException.getReason()),
                                compilationToolFeedback(compilationException,
                                        previousBankPlanJson)));
                        llmReq.setPreviousBankQueryPlanJson(previousBankPlanJson);
                        lastToolFailureSignature = signature;
                    } else {
                        publishBankRoutingAttemptTelemetry(queryCtx.getParseResp(), llmReq, false,
                                candidateRejectionState, candidateValidationErrorType,
                                candidateCompilerReason);
                        if (tryBankFreeSqlFallback(queryCtx, llmReq,
                                BankNl2SqlError.compilationFailure(e))) {
                            // Controlled fallback produced a whitelisted candidate; the parse
                            // request must not surface the terminal compile failure anymore.
                            return;
                        }
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
                        if (tryBankFreeSqlFallback(queryCtx, llmReq,
                                BankNl2SqlError.compilationFailure(e))) {
                            return;
                        }
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

    /**
     * Terminal-state interception (design v1 §1): semantically unreachable compile failures with
     * the switch enabled may reroute into the controlled free-SQL fallback channel. Any
     * non-admitted error (and any failure of the fallback itself) keeps the original terminal
     * failure verbatim.
     */
    private boolean tryBankFreeSqlFallback(ChatQueryContext queryCtx, LLMReq llmReq,
            BankNl2SqlError terminalError) {
        try {
            return BankFreeSqlFallbackHook.tryRun(queryCtx, llmReq, terminalError);
        } catch (RuntimeException e) {
            log.error("bank free-SQL fallback failed: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e.getMessage()));
            return false;
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
