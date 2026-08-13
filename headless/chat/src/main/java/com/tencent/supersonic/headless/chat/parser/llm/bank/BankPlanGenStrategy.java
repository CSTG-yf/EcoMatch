package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.chat.intent.BankFinancialIntentRecognizer;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SqlGenStrategyFactory;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model-owned constrained bank planning.
 *
 * <p>
 * The first model response is a requirement contract, the second is an executable semantic plan.
 * This class never creates or rewrites a plan from question rules. The catalog recognizer is used
 * only to validate explicit high-confidence catalog contracts or explain why a model-generated
 * CLARIFY response failed validation; the model must still return the complete requirements
 * contract and plan itself.
 */
@Service
public class BankPlanGenStrategy extends SqlGenStrategy {

    public static final String APP_KEY = "BANK_CONSTRAINED_PLAN";

    private static final Logger KEY_PIPELINE_LOG = LoggerFactory.getLogger("keyPipeline");
    private static final int MAX_REQUIREMENT_ATTEMPTS = 3;
    private static final List<String> CLOSED_METRIC_LIST_MARKERS =
            List.of("待评价指标集合", "待评价指标", "指标清单", "指标集合", "维度与指标映射");
    private static final String CLARIFICATION_RECHECK_MESSAGE =
            "model selected CLARIFY, but this is a validation failure rather than a user turn. "
                    + "Re-read the original question and the complete semantic registry now. "
                    + "Use CLARIFY only when a metric, organization, or time slot is genuinely "
                    + "missing or ambiguous. If the question already supplies those values, "
                    + "return action=EXECUTE with every directly stated metric, organization, "
                    + "and date/range. Do not repeat a generic request for details, and do not "
                    + "treat a broad label beside explicit catalog metrics as missing information.";

    private final BankRequestContractResponseParser requestContractParser =
            new BankRequestContractResponseParser();
    private final BankQueryPlanResponseParser responseParser = new BankQueryPlanResponseParser();
    private final BankPlanCandidateRanker candidateRanker = new BankPlanCandidateRanker();
    private final BankPlanLlmPrefixCache prefixCache = new BankPlanLlmPrefixCache();
    private final BankFinancialIntentRecognizer clarificationEvidenceRecognizer =
            new BankFinancialIntentRecognizer();

    public BankPlanGenStrategy() {
        ChatAppManager.register(APP_KEY,
                ChatApp.builder().name("银行受约束查询计划").description("由大模型生成经过事实目录校验的银行查询计划")
                        .enable(false).appModule(AppModule.CHAT).build());
    }

    @Override
    public String getAppKey() {
        return APP_KEY;
    }

    @Override
    public LLMResp generate(LLMReq llmReq) {
        SemanticIntentHints admissionHints = requireAdmissionHints(llmReq);
        ChatApp chatApp = resolveChatApp(llmReq);
        if (chatApp == null) {
            throw new IllegalArgumentException(
                    "bank constrained plan or S2SQL parser app configuration is required");
        }
        ChatModelConfig modelConfig = configureModel(chatApp.getChatModelConfig());
        ChatLanguageModel model = getChatLanguageModel(modelConfig);

        RequirementsAttempt requirementsAttempt =
                obtainRequirements(llmReq, model, modelConfig, admissionHints);
        BankRequestContract requirements = requirementsAttempt.contract();
        llmReq.setBankRequirementsAttempts(requirementsAttempt.attempts());
        llmReq.setBankRequirementsRepairReasons(requirementsAttempt.repairReasons());
        if (requirements.getAction() == BankRequestContract.Action.CLARIFY) {
            throw BankNl2SqlError.clarificationRequired(requirements.getClarification());
        }
        llmReq.setBankRequestContract(requirements);
        SemanticIntentHints planHints = requirements.toPlanHints(admissionHints);
        // The compiler sees only model-declared semantic requirements, never recognizer guesses.
        llmReq.setSemanticIntentHints(planHints);
        String requirementsJson = JsonUtil.toString(requirements);

        boolean toolRepair = llmReq.getBankPlanToolResult() != null;
        String dynamicUser = toolRepair
                ? BankPlanPromptComposer.buildToolRepairUserContent(llmReq.getQueryText(),
                        requirementsJson, llmReq.getPreviousBankQueryPlanJson(),
                        llmReq.getBankPlanToolResult())
                : BankPlanPromptComposer.buildPlanUserContent(llmReq.getQueryText(),
                        requirementsJson);
        int candidateLimit =
                toolRepair ? 1 : Math.max(1, Math.min(3, llmReq.getBankMaxCandidates()));

        List<BankPlanCandidateRanker.Candidate> candidates = new ArrayList<>();
        BankQueryPlanParseException lastPlanError = null;
        String lastCandidate = null;
        RuntimeException lastModelFailure = null;
        for (int candidateIndex = 0; candidateIndex < candidateLimit; candidateIndex++) {
            String candidate = null;
            try {
                candidate =
                        prefixCache.generate(model, modelConfig, dynamicUser, candidateLimit == 1);
                lastCandidate = candidate;
                candidates.add(candidateRanker.evaluate(
                        parseAndValidatePlan(llmReq.getQueryText(), candidate, planHints),
                        planHints));
            } catch (BankQueryPlanParseException exception) {
                lastPlanError = exception;
                candidates.add(BankPlanCandidateRanker.Candidate
                        .rejected("rejected-plan-" + candidateIndex, exception.getReason().name()));
                PlanRepairAttempt repaired = repairPlan(llmReq, requirementsJson, candidate,
                        exception, model, modelConfig, planHints, candidateIndex);
                candidates.addAll(repaired.candidates());
                lastCandidate = repaired.lastCandidate();
                lastPlanError = repaired.lastError() == null ? lastPlanError : repaired.lastError();
                lastModelFailure = repaired.modelFailure();
                if (lastModelFailure != null) {
                    break;
                }
            } catch (RuntimeException exception) {
                lastModelFailure = exception;
                break;
            }
        }
        if (lastModelFailure != null
                && candidates.stream().noneMatch(BankPlanCandidateRanker.Candidate::isValid)) {
            throw BankNl2SqlError.modelFailure(lastModelFailure);
        }
        try {
            BankPlanCandidateRanker.Selection selection = candidateRanker.select(candidates);
            Map<String, Object> diagnostics = new LinkedHashMap<>(selection.diagnostics());
            diagnostics.put("bank.nl2sql.planSource", toolRepair ? "MODEL_TOOL_REPAIR" : "MODEL");
            diagnostics.put("bank.nl2sql.requirements", requirementsJson);
            diagnostics.put("bank.nl2sql.requirementsAttempts",
                    llmReq.getBankRequirementsAttempts());
            diagnostics.put("bank.nl2sql.requirementsRepairReasons",
                    llmReq.getBankRequirementsRepairReasons());
            diagnostics.put("bankPlanPrefixCache", prefixCache.stats());
            KEY_PIPELINE_LOG.info(
                    "BankPlanGenStrategy selected {} unique model plan candidate(s), rejected={}",
                    selection.getUniqueCandidateCount(), selection.getRejectedCandidateCount());
            return planResponse(llmReq, requirements, selection.getSelected().getPlan(),
                    diagnostics);
        } catch (IllegalArgumentException noCandidate) {
            throw BankNl2SqlError.afterSingleRepair(lastPlanError == null
                    ? new BankQueryPlanParseException(
                            BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                            "no model plan candidate passed the requirements contract")
                    : lastPlanError);
        }
    }

    private SemanticIntentHints requireAdmissionHints(LLMReq llmReq) {
        if (llmReq == null || llmReq.getSemanticIntentHints() == null) {
            throw new IllegalArgumentException(
                    "semantic schema admission hints are required for bank plan generation");
        }
        return llmReq.getSemanticIntentHints();
    }

    private ChatModelConfig configureModel(ChatModelConfig modelConfig) {
        if (modelConfig == null) {
            throw new IllegalArgumentException("bank chat model configuration is required");
        }
        if (prefixCache.isThinkingEnabled()) {
            modelConfig.setJsonFormat(false);
            if (modelConfig.getTimeOut() == null || modelConfig.getTimeOut() < 300L) {
                modelConfig.setTimeOut(300L);
            }
        } else {
            modelConfig.setJsonFormat(true);
            modelConfig.setJsonFormatType("json_object");
        }
        // Structured repair owns retries. Do not multiply a provider timeout underneath it.
        modelConfig.setMaxRetries(0);
        return modelConfig;
    }

    private RequirementsAttempt obtainRequirements(LLMReq llmReq, ChatLanguageModel model,
            ChatModelConfig config, SemanticIntentHints admissionHints) {
        if (llmReq.getBankRequestContract() != null) {
            return new RequirementsAttempt(llmReq.getBankRequestContract(), 0, List.of());
        }
        String candidate = null;
        BankQueryPlanParseException lastError = null;
        List<String> repairReasons = new ArrayList<>();
        int clarificationRechecks = 0;
        for (int attempt = 0; attempt < MAX_REQUIREMENT_ATTEMPTS; attempt++) {
            String user = attempt == 0
                    ? BankPlanPromptComposer.buildRequirementsUserContent(llmReq.getQueryText())
                    : BankPlanPromptComposer.buildRequirementsRepairUserContent(
                            llmReq.getQueryText(), candidate,
                            lastError == null ? "requirements JSON is invalid"
                                    : lastError.getMessage());
            try {
                candidate = prefixCache.generate(model, config, user, attempt == 0);
                BankRequestContract parsed = requestContractParser.parse(candidate, admissionHints);
                validateExplicitClosedMetricList(llmReq.getQueryText(), parsed);
                validateHighConfidenceQueryFamily(llmReq.getQueryText(), parsed);
                if (parsed.getAction() == BankRequestContract.Action.CLARIFY
                        && clarificationRechecks < MAX_REQUIREMENT_ATTEMPTS - 1) {
                    clarificationRechecks++;
                    lastError = new BankQueryPlanParseException(
                            BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                            clarificationRecheckMessage(llmReq.getQueryText()));
                    repairReasons.add("CLARIFICATION_RECHECK");
                    continue;
                }
                return new RequirementsAttempt(parsed, attempt + 1, List.copyOf(repairReasons));
            } catch (BankQueryPlanParseException exception) {
                lastError = exception;
                repairReasons.add(exception.getReason().name());
            } catch (RuntimeException exception) {
                throw BankNl2SqlError.modelFailure(exception);
            }
        }
        throw BankNl2SqlError
                .afterSingleRepair(lastError == null
                        ? new BankQueryPlanParseException(
                                BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                                "model did not return an executable requirements contract")
                        : lastError);
    }

    /**
     * Rejects a model requirement contract that expands or drops an explicitly declared closed
     * metric list. This is validation-only: it returns exact catalog differences to the next model
     * attempt and never mutates the model-owned contract or creates a replacement plan.
     */
    private void validateExplicitClosedMetricList(String queryText,
            BankRequestContract requirements) {
        if (requirements == null
                || requirements.getAction() != BankRequestContract.Action.EXECUTE) {
            return;
        }
        String closedListText = explicitClosedMetricListText(queryText);
        if (closedListText == null) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(closedListText, LocalDate.now());
        Set<String> expectedMetrics = new LinkedHashSet<>();
        evidence.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                .forEach(expectedMetrics::add);
        evidence.getDerivedMetrics().forEach(metric -> {
            expectedMetrics.add(metric.getNumerator());
            expectedMetrics.add(metric.getDenominator());
        });
        if (expectedMetrics.isEmpty()) {
            return;
        }
        Set<String> actualMetrics = new LinkedHashSet<>(requirements.getMetricCodes());
        Set<String> expectedDerived = new LinkedHashSet<>();
        evidence.getDerivedMetrics().stream().map(BankIntentResult.DerivedMetricCandidate::getCode)
                .forEach(expectedDerived::add);
        Set<String> actualDerived = new LinkedHashSet<>();
        requirements.getDerivedMetrics().stream().map(BankQueryPlan.DerivedMetric::getMetricCode)
                .forEach(actualDerived::add);

        Set<String> missing = difference(expectedMetrics, actualMetrics);
        Set<String> unexpected = difference(actualMetrics, expectedMetrics);
        Set<String> missingDerived = difference(expectedDerived, actualDerived);
        Set<String> unexpectedDerived = difference(actualDerived, expectedDerived);
        if (missing.isEmpty() && unexpected.isEmpty() && missingDerived.isEmpty()
                && unexpectedDerived.isEmpty()) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "explicit_closed_metric_list_mismatch: the original question declares a closed "
                        + "metric list; expected metricCodes=" + expectedMetrics
                        + "; model metricCodes=" + actualMetrics + "; missing=" + missing
                        + "; unexpected=" + unexpected + "; expected derivedMetrics="
                        + expectedDerived + "; model derivedMetrics=" + actualDerived
                        + "; derivedMissing=" + missingDerived + "; derivedUnexpected="
                        + unexpectedDerived + ". Regenerate the complete requirements JSON "
                        + "yourself and do not add or remove catalog metrics.");
    }

    private String explicitClosedMetricListText(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null;
        }
        int start = -1;
        for (String marker : CLOSED_METRIC_LIST_MARKERS) {
            int markerIndex = queryText.indexOf(marker);
            if (markerIndex >= 0 && (start < 0 || markerIndex < start)) {
                start = markerIndex;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = queryText.length();
        int delimiterIndex = queryText.indexOf("。", start);
        if (delimiterIndex >= 0) {
            end = delimiterIndex;
        }
        return queryText.substring(start, end);
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new LinkedHashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    /**
     * Validates two unambiguous business query families whose required operands are published in
     * the semantic catalog. The model still owns the requirements JSON; this gate only returns an
     * exact, repairable mismatch and never rewrites or supplements the model output.
     */
    private void validateHighConfidenceQueryFamily(String queryText,
            BankRequestContract requirements) {
        if (queryText == null || requirements == null
                || requirements.getAction() != BankRequestContract.Action.EXECUTE) {
            return;
        }
        if (containsAny(queryText, "全省排第几", "全省排名第几", "全省排名")) {
            validateRankingIntent(requirements);
        }
        if (!isStandalonePointRatioContext(queryText, requirements)) {
            return;
        }
        if (queryText.contains("存款") && queryText.contains("对公") && queryText.contains("个人")
                && containsAny(queryText, "占比", "比重", "比例")) {
            validateQueryFamily("deposit_structure_share_mismatch", requirements,
                    BankIntentType.POINT_QUERY, List.of("ZB003", "ZB004", "ZB001"), Set.of(), false);
        }
        if (containsAny(queryText, "人均利润", "人均净利润")) {
            validateQueryFamily("per_capita_profit_mismatch", requirements, BankIntentType.RATIO,
                    List.of("ZB011", "ZB018"), Set.of("DERIVED_ZB011_DIV_ZB018"), false);
        }
        if (queryText.contains("逾期贷款率") && queryText.contains("不良贷款率")
                && containsAny(queryText, "高多少", "低多少", "相差", "差多少")) {
            validateQueryFamily("risk_rate_pair_mismatch", requirements, BankIntentType.POINT_QUERY,
                    List.of("ZB013", "ZB017"), Set.of(), true);
        }
    }

    private boolean isStandalonePointRatioContext(String queryText,
            BankRequestContract requirements) {
        BankQueryPlan.TimeRange time = requirements.getTime();
        return requirements.getOrganizationCodes().size() == 1 && time != null
                && time.getStartDate() != null && time.getStartDate().equals(time.getEndDate())
                && time.getComparison() == BankQueryPlan.TimeComparison.NONE
                && !containsAny(queryText, "排名", "排行", "趋势", "走势", "同比", "环比", "变化", "变动", "增长",
                        "下降", "最高", "最低", "全省均值", "对比", "比较");
    }

    private void validateQueryFamily(String errorCode, BankRequestContract requirements,
            BankIntentType expectedIntent, List<String> expectedMetricOrder,
            Set<String> expectedDerived, boolean allowEquivalentMetricOrder) {
        Set<String> expectedMetrics = new LinkedHashSet<>(expectedMetricOrder);
        List<String> actualMetricOrder = requirements.getMetricCodes();
        Set<String> actualMetrics = new LinkedHashSet<>(actualMetricOrder);
        Set<String> actualDerived = requirements.getDerivedMetrics().stream()
                .map(BankQueryPlan.DerivedMetric::getMetricCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> missing = difference(expectedMetrics, actualMetrics);
        Set<String> unexpected = difference(actualMetrics, expectedMetrics);
        Set<String> derivedMissing = difference(expectedDerived, actualDerived);
        Set<String> derivedUnexpected = difference(actualDerived, expectedDerived);
        boolean metricOrderMatches = allowEquivalentMetricOrder
                || expectedMetricOrder.equals(actualMetricOrder);
        if (requirements.getIntent() == expectedIntent && metricOrderMatches
                && missing.isEmpty() && unexpected.isEmpty() && derivedMissing.isEmpty()
                && derivedUnexpected.isEmpty()) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                errorCode + ": expected intent=" + expectedIntent + ", metricCodes="
                        + expectedMetricOrder + ", derivedMetrics=" + expectedDerived
                        + "; model intent=" + requirements.getIntent() + ", metricCodes="
                        + actualMetrics + ", derivedMetrics=" + actualDerived + "; missing="
                        + missing + "; unexpected=" + unexpected + "; derivedMissing="
                        + derivedMissing + "; derivedUnexpected=" + derivedUnexpected
                        + ". Regenerate the complete requirements JSON yourself from the semantic "
                        + "catalog; do not rely on backend plan rewriting.");
    }

    private void validateRankingIntent(BankRequestContract requirements) {
        if (requirements.getIntent() == BankIntentType.RANKING) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "explicit_province_ranking_mismatch: the original question explicitly asks for "
                        + "a province rank; expected intent=RANKING but model intent="
                        + requirements.getIntent() + ". Regenerate the complete requirements JSON "
                        + "and preserve the requested VALUE and RANK facts.");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String clarificationRecheckMessage(String queryText) {
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        List<String> slots = new ArrayList<>();
        if (!evidence.getOrganizations().isEmpty()) {
            slots.add("organizationCodes=" + evidence.getOrganizations().stream()
                    .map(org -> org.getCode() + "(" + org.getName() + ")").toList());
        }
        if (!evidence.getMetrics().isEmpty()) {
            slots.add("metricCodes=" + evidence.getMetrics().stream()
                    .map(metric -> metric.getCode() + "(" + metric.getName() + ")").toList());
        }
        if (!evidence.getDerivedMetrics().isEmpty()) {
            slots.add("derivedMetrics=" + evidence.getDerivedMetrics().stream()
                    .map(metric -> metric.getCode() + "(" + metric.getName() + "="
                            + metric.getNumerator() + "/" + metric.getDenominator() + ")")
                    .toList());
        }
        if (evidence.getTime() != null && evidence.getTime().getStartDate() != null
                && evidence.getTime().getEndDate() != null) {
            slots.add("time=" + evidence.getTime().getStartDate() + ".."
                    + evidence.getTime().getEndDate() + " granularity="
                    + evidence.getTime().getGranularity());
        }
        if (slots.isEmpty()) {
            return CLARIFICATION_RECHECK_MESSAGE;
        }
        return CLARIFICATION_RECHECK_MESSAGE
                + " Deterministic catalog validation found these explicit slots in the original "
                + "question: " + String.join("; ", slots) + ". Treat this only as validation "
                + "feedback: regenerate the entire requirements JSON yourself, include all listed "
                + "base operands for each derived metric, and do not return CLARIFY for these slots.";
    }

    private PlanRepairAttempt repairPlan(LLMReq llmReq, String requirementsJson,
            String firstCandidate, BankQueryPlanParseException firstError, ChatLanguageModel model,
            ChatModelConfig config, SemanticIntentHints planHints, int candidateIndex) {
        List<BankPlanCandidateRanker.Candidate> candidates = new ArrayList<>();
        String previous = firstCandidate;
        BankQueryPlanParseException lastError = firstError;
        for (int repair = 1; repair <= 2; repair++) {
            try {
                String repaired = prefixCache.generate(model, config,
                        BankPlanPromptComposer.buildPlanRepairUserContent(llmReq.getQueryText(),
                                requirementsJson, previous, lastError.getMessage()),
                        false);
                previous = repaired;
                candidates.add(candidateRanker.evaluate(
                        parseAndValidatePlan(llmReq.getQueryText(), repaired, planHints),
                        planHints));
                return new PlanRepairAttempt(candidates, previous, lastError, null);
            } catch (BankQueryPlanParseException exception) {
                lastError = exception;
                candidates.add(BankPlanCandidateRanker.Candidate.rejected(
                        "rejected-repair-" + candidateIndex + "-" + repair,
                        exception.getReason().name()));
            } catch (RuntimeException exception) {
                return new PlanRepairAttempt(candidates, previous, lastError, exception);
            }
        }
        return new PlanRepairAttempt(candidates, previous, lastError, null);
    }

    private BankQueryPlan parseAndValidatePlan(String queryText, String candidate,
            SemanticIntentHints planHints) {
        BankQueryPlan plan = responseParser.parse(candidate, planHints);
        validateModelOwnedOutputContract(queryText, plan);
        return plan;
    }

    /**
     * Validates output semantics that cannot be inferred safely by the projector. The model keeps
     * ownership of the complete plan; a mismatch is returned verbatim to the model repair turn.
     */
    private void validateModelOwnedOutputContract(String queryText, BankQueryPlan plan) {
        if (queryText == null || plan == null
                || plan.getAction() != BankQueryPlan.PlanAction.EXECUTE
                || plan.getIntent() != BankIntentType.AGGREGATION) {
            return;
        }
        BankQueryPlan.AggregationResultMode actual =
                plan.getOutput() == null ? null : plan.getOutput().getAggregationMode();
        if (queryText.contains("日均") && !containsAny(queryText, "最高", "最低", "最大", "最小")) {
            requireAggregationMode(actual, BankQueryPlan.AggregationResultMode.AVERAGE_ONLY,
                    "daily_average_output_mode_mismatch");
        }
        if (containsAny(queryText, "最高", "最低", "最大", "最小")) {
            requireAggregationMode(actual, BankQueryPlan.AggregationResultMode.WITH_EXTREMA,
                    "aggregation_extrema_output_mode_mismatch");
        }
    }

    private void requireAggregationMode(BankQueryPlan.AggregationResultMode actual,
            BankQueryPlan.AggregationResultMode expected, String errorCode) {
        if (actual == expected) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                errorCode + ": expected output.aggregationMode=" + expected + " but model returned "
                        + actual
                        + ". Regenerate the complete PLAN JSON yourself and preserve all other "
                        + "validated requirements.");
    }

    private LLMResp planResponse(LLMReq llmReq, BankRequestContract requirements,
            BankQueryPlan plan, Map<String, Object> diagnostics) {
        LLMResp response = new LLMResp();
        response.setQuery(llmReq.getQueryText());
        response.setBankRequestContract(requirements);
        response.setBankQueryPlan(plan);
        response.setBankCandidateDiagnostics(diagnostics);
        return response;
    }

    private ChatApp resolveChatApp(LLMReq llmReq) {
        if (llmReq.getChatAppConfig() == null) {
            return null;
        }
        ChatApp dedicatedApp = llmReq.getChatAppConfig().get(APP_KEY);
        if (dedicatedApp != null && dedicatedApp.getChatModelConfig() != null) {
            return dedicatedApp;
        }
        ChatApp s2SqlApp = llmReq.getChatAppConfig().get(OnePassSCSqlGenStrategy.APP_KEY);
        if (s2SqlApp != null && s2SqlApp.getChatModelConfig() != null) {
            return s2SqlApp;
        }
        return null;
    }

    @Override
    public void afterPropertiesSet() {
        SqlGenStrategyFactory.addSqlGenerationForFactory(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN,
                this);
    }

    private record PlanRepairAttempt(List<BankPlanCandidateRanker.Candidate> candidates,
            String lastCandidate, BankQueryPlanParseException lastError,
            RuntimeException modelFailure) {}

    private record RequirementsAttempt(BankRequestContract contract, int attempts,
            List<String> repairReasons) {}
}
