package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.chat.intent.BankFinancialIntentRecognizer;
import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon;
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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern EXPLICIT_YEAR_END_RANGE = Pattern.compile(
            "从\\s*(20\\d{2})年(?:末|底|年末|年底)\\s*(?:到|至)\\s*"
                    + "(20\\d{2})[-/]([0-1]?\\d)[-/]([0-3]?\\d)");

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
        List<String> requirementsRepairCodes = requirementsAttempt.repairCodes();
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
        List<String> planRepairCodes = new ArrayList<>();
        BankQueryPlanParseException lastPlanError = null;
        String lastCandidate = null;
        RuntimeException lastModelFailure = null;
        for (int candidateIndex = 0; candidateIndex < candidateLimit; candidateIndex++) {
            String candidate = null;
            try {
                candidate = prefixCache.generate(model, modelConfig,
                        BankPlanLlmPrefixCache.Stage.PLAN, dynamicUser, candidateLimit == 1);
                lastCandidate = candidate;
                candidates.add(candidateRanker.evaluate(
                        parseAndValidatePlan(llmReq.getQueryText(), candidate, planHints),
                        planHints));
            } catch (BankQueryPlanParseException exception) {
                lastPlanError = exception;
                planRepairCodes.add(repairErrorCode(exception));
                candidates.add(BankPlanCandidateRanker.Candidate
                        .rejected("rejected-plan-" + candidateIndex, exception.getReason().name()));
                PlanRepairAttempt repaired = repairPlan(llmReq, requirementsJson, candidate,
                        exception, model, modelConfig, planHints, candidateIndex);
                candidates.addAll(repaired.candidates());
                planRepairCodes.addAll(repaired.repairCodes());
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
            diagnostics.put("bank.nl2sql.requirementsRepairCodes",
                    List.copyOf(requirementsRepairCodes));
            diagnostics.put("bank.nl2sql.planRepairCodes", List.copyOf(planRepairCodes));
            diagnostics.put("bankPlanPrefixCache", prefixCache.stats());
            KEY_PIPELINE_LOG.info(
                    "BankPlanGenStrategy selected {} unique model plan candidate(s), rejected={}, planRepairCodes={}",
                    selection.getUniqueCandidateCount(), selection.getRejectedCandidateCount(),
                    planRepairCodes);
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
            modelConfig.setJsonFormatType("json_schema");
        }
        // Structured repair owns retries. Do not multiply a provider timeout underneath it.
        modelConfig.setMaxRetries(0);
        return modelConfig;
    }

    private RequirementsAttempt obtainRequirements(LLMReq llmReq, ChatLanguageModel model,
            ChatModelConfig config, SemanticIntentHints admissionHints) {
        if (llmReq.getBankRequestContract() != null) {
            return new RequirementsAttempt(llmReq.getBankRequestContract(), 0, List.of(),
                    List.of());
        }
        String candidate = null;
        BankQueryPlanParseException lastError = null;
        List<String> repairReasons = new ArrayList<>();
        List<String> repairCodes = new ArrayList<>();
        int clarificationRechecks = 0;
        for (int attempt = 0; attempt < MAX_REQUIREMENT_ATTEMPTS; attempt++) {
            String user = attempt == 0
                    ? BankPlanPromptComposer.buildRequirementsUserContent(llmReq.getQueryText())
                    : BankPlanPromptComposer.buildRequirementsRepairUserContent(
                            llmReq.getQueryText(), candidate,
                            lastError == null ? "requirements JSON is invalid"
                                    : lastError.getMessage());
            try {
                candidate = prefixCache.generate(model, config,
                        BankPlanLlmPrefixCache.Stage.REQUIREMENTS, user, attempt == 0);
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
                    repairCodes.add("CLARIFICATION_RECHECK");
                    logRepair("REQUIREMENTS", attempt + 1, "CLARIFICATION_RECHECK", lastError);
                    continue;
                }
                return new RequirementsAttempt(parsed, attempt + 1, List.copyOf(repairReasons),
                        List.copyOf(repairCodes));
            } catch (BankQueryPlanParseException exception) {
                lastError = exception;
                repairReasons.add(exception.getReason().name());
                repairCodes.add(repairErrorCode(exception));
                logRepair("REQUIREMENTS", attempt + 1, repairErrorCode(exception), exception);
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
     * Extracts the stable error code that prefixes validator messages ({@code snake_case: ...}).
     * Structured repair diagnostics must never collapse into a bare {@code VALIDATION_FAILED}.
     */
    static String repairErrorCode(BankQueryPlanParseException exception) {
        if (exception == null) {
            return "UNKNOWN";
        }
        String message = exception.getMessage();
        if (message != null) {
            int colon = message.indexOf(':');
            if (colon > 0) {
                String candidate = message.substring(0, colon).trim();
                if (candidate.matches("[a-z][a-z0-9_]{2,63}")) {
                    return candidate;
                }
            }
        }
        return exception.getReason().name();
    }

    private void logRepair(String stage, int attempt, String code,
            BankQueryPlanParseException exception) {
        String message =
                exception == null || exception.getMessage() == null ? "" : exception.getMessage();
        KEY_PIPELINE_LOG.info(
                "BankPlanGenStrategy repair stage={} attempt={} code={} reason={} detail=[{}]",
                stage, attempt, code, exception == null ? "NONE" : exception.getReason().name(),
                message.length() > 160 ? message.substring(0, 160) : message);
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
        if (queryText == null || requirements == null) {
            return;
        }
        // These fully specified families must be validated before the generic CLARIFY exit.
        // The recognizer supplies only evidence for a repair message; the model still owns the
        // complete requirements contract and executable plan.
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        validateMonthAndYearComparison(queryText, requirements);
        validateExplicitYearEndRange(queryText, requirements);
        validateExplicitProvinceBottomRanking(queryText, requirements);
        validateProvinceWideInstitutionRanking(queryText, requirements);
        validateDaysAboveProvinceAverage(queryText, requirements);
        validateStructureEqualityFamily(queryText, requirements, evidence);
        // Validation only: the model still supplies the identifiers, order and intent. The
        // catalog recognizer is used to return a repairable error when a complete two-operand
        // point ratio is incorrectly clarified or classified as another query family.
        validateGenericPointRatioQuery(queryText, requirements);
        validateStructureShareFamily(queryText, requirements, evidence);
        if (requirements.getAction() != BankRequestContract.Action.EXECUTE) {
            return;
        }
        if (isProvinceRankSelectionQuestion(queryText)) {
            validateRankingIntent(requirements);
        }
        validateRankedChangeFamily(requirements, evidence);
        validateSelectedOrganizationRanking(queryText, requirements);
        validateOrganizationComparison(queryText, requirements);
        validateSelectedOrganizationBestComparison(queryText, requirements);
        if (queryText.contains("全省均值") && containsAny(queryText, "逐一对比", "逐项对比", "分别对比")) {
            validateProvinceAverageComparison(queryText, requirements);
        }
        if (isEndpointChangeDirectionQuery(queryText)) {
            validateExpectedIntent("endpoint_change_direction_mismatch", requirements,
                    BankIntentType.CHANGE);
        }
        if (!isStandalonePointRatioContext(queryText, requirements)) {
            return;
        }
        validateDerivedPointRatioQuery(queryText, requirements, evidence);
        if (queryText.contains("不良贷款余额") && queryText.contains("贷款总额")
                && containsAny(queryText, "占", "比重", "比例")) {
            validateQueryFamily("loan_share_ratio_mismatch", requirements, BankIntentType.RATIO,
                    List.of("ZB014", "ZB002"), Set.of(), false);
        }
        validateMetricPairGapQuery(queryText, requirements, evidence);
    }

    /**
     * Slot-driven province rank selection: ranking wording plus a province-wide scope, or any of
     * the legacy literal phrases. Change-family wording is excluded because those questions keep
     * their CHANGE contract even when the word 排名 appears.
     */
    private boolean isProvinceRankSelectionQuestion(String queryText) {
        if (containsAny(queryText, "全省排第几", "全省排名第几", "全省排名")) {
            return true;
        }
        return containsAny(queryText, "排第", "排名第几", "位列第", "名次")
                && containsAny(queryText, "全省", "13家", "十三家", "各家", "全体机构", "所有农商行", "全部农商行")
                && !containsAny(queryText, "同比", "环比", "变化", "变动", "增幅", "增长", "下降", "较上月", "较去年同期",
                        "较年初");
    }

    /**
     * Validates ranking-over-change questions (增幅/降幅排名). The only compilable contract keeps
     * intent=CHANGE with an explicit non-NONE comparison: the ranking rides on limit plus answer
     * facts, and a RANKING-labeled contract cannot carry that comparison (a requirements non-NONE
     * comparison implies intent=CHANGE). The trigger is slot-driven — a rank signal plus change
     * wording over a population without named organizations.
     */
    private void validateRankedChangeFamily(BankRequestContract requirements,
            BankIntentResult evidence) {
        boolean rankSignal =
                containsAny(evidence.getOriginalText(), "排名", "排第", "前三", "后三", "前3", "后3", "第几")
                        || evidence.getFilters().stream()
                                .anyMatch(filter -> "rank".equals(filter.getField())
                                        || "rank_from_bottom".equals(filter.getField()));
        boolean changeSignal = containsAny(evidence.getOriginalText(), "增幅", "降幅", "涨跌幅", "变化幅度",
                "增长最快", "下降最快", "增长最多", "下降最多");
        if (!rankSignal || !changeSignal || !evidence.getOrganizations().isEmpty()
                || evidence.getMetrics().isEmpty()) {
            return;
        }
        validateExpectedIntent("ranked_change_family_mismatch", requirements,
                BankIntentType.CHANGE);
    }

    /**
     * Validates equality/gap questions for any catalog composition group as a point multi-metric
     * query. The slot trigger follows recognizer evidence so alias paraphrases of both parts reach
     * the same repairable contract; the legacy substring trigger stays because alias matching
     * cannot span connector words (对公与个人存款). The model owns the complete requirements JSON.
     */
    private void validateStructureEqualityFamily(String queryText, BankRequestContract requirements,
            BankIntentResult evidence) {
        if (!containsAny(queryText, "是不是等于", "是否等于", "加起来", "合起来", "合计", "总和", "差额", "差多少", "相加",
                "之和", "加总")) {
            return;
        }
        if (evidence.getOrganizations().size() != 1 || evidence.getTime() == null
                || evidence.getTime().isAmbiguous()) {
            return;
        }
        Set<String> recognizedMetrics = evidenceMetricCodes(evidence);
        for (BankFinancialLexicon.CompositionGroupDefinition group : BankFinancialLexicon
                .compositionGroups()) {
            boolean legacy =
                    "deposit".equals(compositionFamilyCode(group)) && queryText.contains("存款")
                            && queryText.contains("对公") && queryText.contains("个人");
            if (!recognizedMetrics.containsAll(group.getPartCodes()) && !legacy) {
                continue;
            }
            validateStructureEquality(compositionFamilyCode(group) + "_structure_equality_mismatch",
                    requirements, evidence, group.orderedCodes());
        }
    }

    /**
     * Validates composition share questions (both parts of a catalog group, plus the total or an
     * explicit structure word) as a point multi-metric query. Part-to-part ratio questions stay
     * with the generic point-ratio family; change-family wording never enters this contract.
     */
    private void validateStructureShareFamily(String queryText, BankRequestContract requirements,
            BankIntentResult evidence) {
        if (requirements.getAction() != BankRequestContract.Action.EXECUTE
                || !containsAny(queryText, "占比", "比重", "比例", "份额")) {
            return;
        }
        boolean legacyLoan = queryText.contains("个人贷款") && queryText.contains("对公贷款")
                && queryText.contains("各项贷款");
        boolean legacyDeposit =
                queryText.contains("存款") && queryText.contains("对公") && queryText.contains("个人")
                        && isStandalonePointRatioContext(queryText, requirements);
        boolean changeFamily = containsAny(queryText, "排名", "排行", "趋势", "走势", "同比", "环比", "变化",
                "变动", "增长", "下降", "逐月", "逐季", "逐日");
        Set<String> recognizedMetrics = evidenceMetricCodes(evidence);
        for (BankFinancialLexicon.CompositionGroupDefinition group : BankFinancialLexicon
                .compositionGroups()) {
            boolean legacy =
                    "loan".equals(compositionFamilyCode(group)) ? legacyLoan : legacyDeposit;
            boolean slots = recognizedMetrics.containsAll(group.getPartCodes())
                    && (containsAny(queryText, "构成", "结构", "份额")
                            || recognizedMetrics.contains(group.getTotalCode()));
            if (!legacy && !(slots && !changeFamily)) {
                continue;
            }
            validateQueryFamily(compositionFamilyCode(group) + "_structure_share_mismatch",
                    requirements, BankIntentType.POINT_QUERY, group.orderedCodes(), Set.of(),
                    false);
        }
    }

    private String compositionFamilyCode(BankFinancialLexicon.CompositionGroupDefinition group) {
        return group.getName().startsWith("存款") ? "deposit" : "loan";
    }

    /**
     * Validates any recognized derived metric (存贷比/净利润率/人均利润) as a standalone point ratio:
     * intent=RATIO with both base operands and the derived specification. The per-capita contract
     * keeps its legacy error code so existing diagnostics stay comparable.
     */
    private void validateDerivedPointRatioQuery(String queryText, BankRequestContract requirements,
            BankIntentResult evidence) {
        for (BankIntentResult.DerivedMetricCandidate derived : evidence.getDerivedMetrics()) {
            String errorCode = "DERIVED_ZB011_DIV_ZB018".equals(derived.getCode())
                    ? "per_capita_profit_mismatch"
                    : "derived_point_ratio_mismatch";
            validateQueryFamily(errorCode, requirements, BankIntentType.RATIO,
                    List.of(derived.getNumerator(), derived.getDenominator()),
                    Set.of(derived.getCode()), false);
        }
    }

    /**
     * Validates a same-date gap between two metrics of one organization as a point multi-metric
     * query. The legacy risk-rate substring trigger is kept as a fallback so every question that
     * reached validation before still reaches it; slot evidence extends the family to any metric
     * pair.
     */
    private void validateMetricPairGapQuery(String queryText, BankRequestContract requirements,
            BankIntentResult evidence) {
        boolean gapSignal = containsAny(queryText, "高多少", "低多少", "相差", "差多少", "多多少", "少多少", "差距");
        if (!gapSignal) {
            return;
        }
        boolean legacyRiskPair = queryText.contains("逾期贷款率") && queryText.contains("不良贷款率");
        List<String> pair = evidence.getMetrics().stream()
                .map(BankIntentResult.MetricCandidate::getCode).toList();
        if (!legacyRiskPair && !(evidence.getOrganizations().size() == 1 && pair.size() == 2)) {
            return;
        }
        if (!legacyRiskPair && pair.size() != 2) {
            return;
        }
        String errorCode =
                new LinkedHashSet<>(pair).equals(new LinkedHashSet<>(List.of("ZB013", "ZB017")))
                        || legacyRiskPair ? "risk_rate_pair_mismatch" : "metric_pair_gap_mismatch";
        List<String> expected =
                legacyRiskPair && pair.size() != 2 ? List.of("ZB013", "ZB017") : pair;
        validateQueryFamily(errorCode, requirements, BankIntentType.POINT_QUERY, expected, Set.of(),
                true);
    }

    private Set<String> evidenceMetricCodes(BankIntentResult evidence) {
        return evidence.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * A selected set of explicitly named institutions is a local ranking, not a pairwise
     * comparison. This is validation-only: the model still supplies the identifiers and complete
     * contract; a mismatch is sent back as a repairable error instead of being rewritten here.
     */
    private void validateSelectedOrganizationRanking(String queryText,
            BankRequestContract requirements) {
        if (queryText == null || requirements == null || !containsAny(queryText, "谁", "哪家", "哪个")
                || !containsAny(queryText, "最多", "最少", "最高", "最低", "最大", "最小")
                || queryText.contains("全省均值")) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (evidence.getOrganizations().size() < 2 || evidence.getMetrics().size() != 1) {
            return;
        }
        Set<String> expectedOrganizations =
                evidence.getOrganizations().stream().map(BankIntentResult.OrganizationSlot::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualOrganizations = new LinkedHashSet<>(requirements.getOrganizationCodes());
        Set<String> expectedMetrics =
                evidence.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualMetrics = new LinkedHashSet<>(requirements.getMetricCodes());
        if (requirements.getIntent() == BankIntentType.RANKING
                && actualOrganizations.equals(expectedOrganizations)
                && actualMetrics.equals(expectedMetrics)) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "selected_organization_ranking_mismatch: questions asking which of the explicitly "
                        + "named institutions has the highest/lowest value require intent=RANKING, "
                        + "organizationCodes=" + expectedOrganizations + ", metricCodes="
                        + expectedMetrics + "; model intent=" + requirements.getIntent()
                        + ", organizationCodes=" + actualOrganizations + ", metricCodes="
                        + actualMetrics + ". Regenerate the complete requirements JSON; do not "
                        + "rewrite the model plan in the backend.");
    }

    /**
     * Validates the wording "from an explicit YYYY year end to an explicit date". An explicit
     * year is not a relative "last year end" expression; this gate returns the exact repairable
     * dates and never mutates the model contract.
     */
    private void validateExplicitYearEndRange(String queryText,
            BankRequestContract requirements) {
        if (queryText == null || requirements == null) {
            return;
        }
        Matcher matcher = EXPLICIT_YEAR_END_RANGE.matcher(queryText);
        if (!matcher.find()) {
            return;
        }
        LocalDate expectedBaseline;
        LocalDate expectedCurrent;
        try {
            expectedBaseline = LocalDate.of(Integer.parseInt(matcher.group(1)), 12, 31);
            expectedCurrent = LocalDate.of(Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
        } catch (DateTimeException | NumberFormatException invalidDate) {
            return;
        }
        BankQueryPlan.TimeRange actual = requirements.getTime();
        boolean valid = requirements.getAction() == BankRequestContract.Action.EXECUTE
                && requirements.getIntent() == BankIntentType.CHANGE && actual != null
                && actual.getStartDate() != null && actual.getEndDate() != null
                && actual.getBaselineStartDate() != null && actual.getBaselineEndDate() != null
                && actual.getStartDate().equals(expectedCurrent)
                && actual.getEndDate().equals(expectedCurrent)
                && actual.getBaselineStartDate().equals(expectedBaseline)
                && actual.getBaselineEndDate().equals(expectedBaseline);
        if (valid) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "explicit_year_end_range_mismatch: an explicitly written YYYY year-end is that "
                        + "year's 12-31, not the prior year's end; expected current="
                        + expectedCurrent + ", baseline=" + expectedBaseline + "; model time="
                        + actual + ". Regenerate the complete requirements JSON without changing "
                        + "the user's explicit endpoints.");
    }

    /**
     * Validates "which of these named institutions is best" as a full comparison. It is separate
     * from the ranking validator because "最好/控制得最好" does not request a rank slice.
     */
    private void validateSelectedOrganizationBestComparison(String queryText,
            BankRequestContract requirements) {
        if (queryText == null || requirements == null
                || !containsAny(queryText, "谁", "哪家", "哪个")
                || !containsAny(queryText, "最好", "最优", "控制得最好", "表现最好")
                || queryText.contains("全省均值")) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (evidence.getOrganizations().size() < 2 || evidence.getMetrics().size() != 1
                || !evidence.getDerivedMetrics().isEmpty()) {
            return;
        }
        Set<String> expectedOrganizations = evidence.getOrganizations().stream()
                .map(BankIntentResult.OrganizationSlot::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> expectedMetrics = evidence.getMetrics().stream()
                .map(BankIntentResult.MetricCandidate::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<BankRequestContract.AnswerFactType> expectedFacts =
                List.of(BankRequestContract.AnswerFactType.VALUE,
                        BankRequestContract.AnswerFactType.GAP_VALUE);
        Set<String> actualOrganizations = new LinkedHashSet<>(
                safeList(requirements.getOrganizationCodes()));
        Set<String> actualMetrics = new LinkedHashSet<>(safeList(requirements.getMetricCodes()));
        boolean hasRankFilter = safeList(requirements.getFilters()).stream().anyMatch(filter ->
                filter != null && ("rank".equals(filter.getField())
                        || "rank_from_bottom".equals(filter.getField())));
        boolean valid = requirements.getAction() == BankRequestContract.Action.EXECUTE
                && requirements.getIntent() == BankIntentType.COMPARISON
                && actualOrganizations.equals(expectedOrganizations)
                && actualMetrics.equals(expectedMetrics)
                && requirements.getRequiredLimit() == null && !hasRankFilter
                && expectedFacts.equals(safeList(requirements.getAnswerFactTypes()));
        if (valid) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "selected_organization_best_comparison_mismatch: questions asking which of the "
                        + "explicitly named institutions is best require intent=COMPARISON, all "
                        + "named organizations, one metric, VALUE/GAP_VALUE, no rank filter and "
                        + "requiredLimit=null; expected organizations=" + expectedOrganizations
                        + ", metrics=" + expectedMetrics + "; model intent="
                        + requirements.getIntent() + ", organizations=" + actualOrganizations
                        + ", metrics=" + actualMetrics
                        + ", answerFactTypes=" + safeList(requirements.getAnswerFactTypes())
                        + ". Regenerate the complete requirements JSON; do not rewrite the model "
                        + "plan in the backend.");
    }

    /** Validates an explicit two-institution value difference without changing model output. */
    private void validateOrganizationComparison(String queryText,
            BankRequestContract requirements) {
        if (queryText == null || requirements == null || queryText.contains("全省均值")
                || containsAny(queryText, "同比", "环比", "较上月", "较去年", "较年初", "增幅", "变动")
                || !containsAny(queryText, "相差", "差多少", "多多少", "少多少")
                        && !(queryText.contains("比") && !queryText.contains("比例")
                                && !queryText.contains("占比") && !queryText.contains("比重"))) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (evidence.getOrganizations().size() != 2 || evidence.getMetrics().size() != 1) {
            return;
        }
        Set<String> expectedOrganizations =
                evidence.getOrganizations().stream().map(BankIntentResult.OrganizationSlot::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualOrganizations = new LinkedHashSet<>(requirements.getOrganizationCodes());
        Set<String> expectedMetrics =
                evidence.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualMetrics = new LinkedHashSet<>(requirements.getMetricCodes());
        boolean answerFactsOk =
                requirements.getAnswerFactTypes().contains(BankRequestContract.AnswerFactType.VALUE)
                        && requirements.getAnswerFactTypes()
                                .contains(BankRequestContract.AnswerFactType.GAP_VALUE);
        if (requirements.getIntent() == BankIntentType.COMPARISON
                && actualOrganizations.equals(expectedOrganizations)
                && actualMetrics.equals(expectedMetrics) && answerFactsOk) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "organization_comparison_mismatch: explicit A versus B difference questions require "
                        + "intent=COMPARISON, both organizations, one metric, and VALUE/GAP_VALUE; "
                        + "expected organizations=" + expectedOrganizations + ", metrics="
                        + expectedMetrics + "; model intent=" + requirements.getIntent()
                        + ", organizations=" + actualOrganizations + ", metrics=" + actualMetrics
                        + ", answerFactTypes=" + requirements.getAnswerFactTypes()
                        + ". Regenerate the complete requirements JSON without changing the question.");
    }

    /** Ensures the daily count contract reaches the compiler as an executable aggregation plan. */
    private void validateDaysAboveProvinceAverage(String queryText,
            BankRequestContract requirements) {
        if (!isDailyProvinceAverageCountQuestion(queryText)) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (evidence.getOrganizations().size() != 1 || evidence.getMetrics().size() != 1) {
            return;
        }
        Set<String> expectedOrganizations =
                evidence.getOrganizations().stream().map(BankIntentResult.OrganizationSlot::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualOrganizations =
                new LinkedHashSet<>(safeList(requirements.getOrganizationCodes()));
        Set<String> expectedMetrics =
                evidence.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualMetrics = new LinkedHashSet<>(safeList(requirements.getMetricCodes()));
        List<BankQueryPlan.Filter> filters = safeList(requirements.getFilters());
        boolean metricDirectionPresent = filters.stream().anyMatch(filter -> filter != null
                && "metric_value".equals(filter.getField())
                && ("GT".equals(filter.getOperator()) || "GTE".equals(filter.getOperator())
                        || "LT".equals(filter.getOperator()) || "LTE".equals(filter.getOperator()))
                && "PROVINCE_AVERAGE".equals(filter.getValue()));
        if (metricDirectionPresent) {
            throw new BankQueryPlanParseException(
                    BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                    "days_above_province_average_metric_filter_forbidden: the daily count query "
                            + "family encodes the high direction in its calculation; "
                            + "requirements.filters must contain only benchmark/COMPARE/"
                            + "PROVINCE_AVERAGE. Remove the metric_value direction filter and "
                            + "regenerate the complete requirements JSON.");
        }
        boolean benchmarkPresent = filters.stream()
                .anyMatch(filter -> filter != null && "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()));
        boolean countRequested = safeList(requirements.getAnswerFactTypes())
                .contains(BankRequestContract.AnswerFactType.COUNT);
        if (requirements.getIntent() == BankIntentType.AGGREGATION
                && actualOrganizations.equals(expectedOrganizations)
                && actualMetrics.equals(expectedMetrics) && benchmarkPresent && countRequested) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "days_above_province_average_mismatch: daily count questions require "
                        + "intent=AGGREGATION, one organization, one metric, benchmark/COMPARE/"
                        + "PROVINCE_AVERAGE, and COUNT; expected organizations="
                        + expectedOrganizations + ", metrics=" + expectedMetrics + "; model intent="
                        + requirements.getIntent() + ", organizations=" + actualOrganizations
                        + ", metrics=" + actualMetrics + ", benchmarkPresent=" + benchmarkPresent
                        + ", answerFactTypes=" + requirements.getAnswerFactTypes()
                        + ". Regenerate the complete requirements JSON for the daily fact table.");
    }

    private boolean isDailyProvinceAverageCountQuestion(String queryText) {
        if (queryText == null || !containsAny(queryText, "全省均值", "全省平均", "平均水平")
                || !containsAny(queryText, "多少天", "几天", "天数", "多少个交易日")
                || !containsAny(queryText, "高于", "超过", "大于")
                || containsAny(queryText, "低于", "小于", "排名", "第几", "趋势", "走势", "逐月", "逐季", "逐日",
                        "环比", "同比", "较年初", "较上季", "较上月", "较同期", "增幅", "增量", "变动", "变化")) {
            return false;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        return evidence.getOrganizations().size() == 1 && evidence.getMetrics().size() == 1
                && evidence.getTime() != null && !evidence.getTime().isAmbiguous();
    }

    private void validateMonthAndYearComparison(String queryText,
            BankRequestContract requirements) {
        if (queryText == null || !containsAny(queryText, "环比", "较上月")
                || !containsAny(queryText, "同比", "较去年同期")) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (evidence.getOrganizations().size() != 1 || evidence.getMetrics().size() != 1
                || evidence.getTime() == null || evidence.getTime().isAmbiguous()) {
            return;
        }
        List<String> expectedOrganizations = evidence.getOrganizations().stream()
                .map(BankIntentResult.OrganizationSlot::getCode).toList();
        List<String> expectedMetrics = evidence.getMetrics().stream()
                .map(BankIntentResult.MetricCandidate::getCode).toList();
        BankQueryPlan.TimeRange actualTime = requirements.getTime();
        boolean valid = requirements.getAction() == BankRequestContract.Action.EXECUTE
                && requirements.getIntent() == BankIntentType.CHANGE
                && expectedOrganizations.equals(safeList(requirements.getOrganizationCodes()))
                && expectedMetrics.equals(safeList(requirements.getMetricCodes()))
                && actualTime != null
                && actualTime.getComparison() == BankQueryPlan.TimeComparison.MOM_AND_YOY
                && actualTime.getBaselineStartDate() == null
                && actualTime.getBaselineEndDate() == null;
        if (valid) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "mom_and_yoy_requirements_mismatch: a fully specified same-date month-and-year "
                        + "comparison requires action=EXECUTE, intent=CHANGE, exactly one recognized "
                        + "organization and metric, comparison=MOM_AND_YOY, and null baseline dates; "
                        + "expected organizations=" + expectedOrganizations + ", metrics="
                        + expectedMetrics + ", model action=" + requirements.getAction()
                        + ", intent=" + requirements.getIntent() + ", organizations="
                        + safeList(requirements.getOrganizationCodes()) + ", metrics="
                        + safeList(requirements.getMetricCodes()) + ", time=" + actualTime
                        + ". Regenerate the complete requirements JSON; the compiler derives both "
                        + "comparison baselines from the current date.");
    }

    private void validateExplicitProvinceBottomRanking(String queryText,
            BankRequestContract requirements) {
        if (queryText == null
                || !containsAny(queryText, "全省", "全省内", "全省范围", "所有农商行", "全部农商行", "各家机构", "全体机构",
                        "13家", "十三家")
                || !containsAny(queryText, "最后", "倒数", "排名后", "排后")
                || !containsAny(queryText, "哪家", "哪一", "哪个", "谁")) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        List<String> expectedMetrics = evidence.getMetrics().stream()
                .map(BankIntentResult.MetricCandidate::getCode).toList();
        BankIntentResult.FilterSlot expectedBottomRank = evidence.getFilters().stream()
                .filter(filter -> "rank_from_bottom".equals(filter.getField())
                        && "LTE".equals(filter.getOperator()) && filter.getValue() != null)
                .findFirst().orElse(null);
        // A named organization is a point-in-population rank, not a province-wide institution
        // selection. Leave that contract to the existing selected-organization ranking path.
        if (!evidence.getOrganizations().isEmpty() || expectedMetrics.size() != 1
                || evidence.getTime() == null || evidence.getTime().isAmbiguous()
                || expectedBottomRank == null) {
            return;
        }
        String expectedLimit = expectedBottomRank.getValue();
        BankQueryPlan.TimeRange actualTime = requirements.getTime();
        List<BankQueryPlan.Filter> filters = safeList(requirements.getFilters());
        boolean valid = requirements.getAction() == BankRequestContract.Action.EXECUTE
                && requirements.getIntent() == BankIntentType.RANKING
                && expectedMetrics.equals(safeList(requirements.getMetricCodes()))
                && safeList(requirements.getOrganizationCodes()).isEmpty() && actualTime != null
                && evidence.getTime().getStartDate().equals(actualTime.getStartDate())
                && evidence.getTime().getEndDate().equals(actualTime.getEndDate())
                && expectedLimit.equals(String.valueOf(requirements.getRequiredLimit()))
                && filters.stream()
                        .anyMatch(filter -> filter != null
                                && "rank_from_bottom".equals(filter.getField())
                                && "LTE".equals(filter.getOperator())
                                && expectedLimit.equals(filter.getValue()));
        if (valid) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "explicit_province_bottom_ranking_mismatch: a fully specified province bottom-rank "
                        + "selection requires action=EXECUTE, intent=RANKING, organizationCodes=[], "
                        + "one recognized metric, the explicit date, rank_from_bottom/LTE/"
                        + expectedLimit + ", and requiredLimit=" + expectedLimit + "; model action="
                        + requirements.getAction() + ", intent=" + requirements.getIntent()
                        + ", metrics=" + safeList(requirements.getMetricCodes())
                        + ", organizations=" + safeList(requirements.getOrganizationCodes())
                        + ", time=" + actualTime + ", filters=" + filters + ", requiredLimit="
                        + requirements.getRequiredLimit()
                        + ". Regenerate the complete requirements JSON; do not ask for a specific "
                        + "organization when the question asks which institution in the province.");
    }

    /**
     * Validates a complete whole-population "which institution is highest/lowest" question.
     *
     * <p>
     * The recognizer supplies only the slots that make the contract unambiguous. In particular, an
     * empty organization list is intentional here: "which bank" selects from the whole catalog,
     * whereas a named organization belongs to the selected-organization ranking path above.
     */
    private void validateProvinceWideInstitutionRanking(String queryText,
            BankRequestContract requirements) {
        if (!isProvinceWideInstitutionRankingQuestion(queryText)) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (!evidence.getOrganizations().isEmpty() || evidence.getMetrics().size() != 1
                || evidence.getTime() == null || evidence.getTime().isAmbiguous()) {
            return;
        }
        BankIntentResult.FilterSlot expectedRank = evidence.getFilters().stream()
                .filter(filter -> ("rank".equals(filter.getField())
                        || "rank_from_bottom".equals(filter.getField()))
                        && "LTE".equals(filter.getOperator()) && filter.getValue() != null)
                .findFirst().orElse(null);
        if (expectedRank == null) {
            return;
        }
        List<String> expectedMetrics = evidence.getMetrics().stream()
                .map(BankIntentResult.MetricCandidate::getCode).toList();
        String expectedLimit = expectedRank.getValue();
        BankQueryPlan.TimeRange actualTime = requirements.getTime();
        List<BankQueryPlan.Filter> filters = safeList(requirements.getFilters());
        List<BankQueryPlan.Filter> rankFilters = filters.stream()
                .filter(filter -> filter != null && ("rank".equals(filter.getField())
                        || "rank_from_bottom".equals(filter.getField())))
                .toList();
        boolean valid = requirements.getAction() == BankRequestContract.Action.EXECUTE
                && requirements.getIntent() == BankIntentType.RANKING
                && expectedMetrics.equals(safeList(requirements.getMetricCodes()))
                && safeList(requirements.getOrganizationCodes()).isEmpty() && actualTime != null
                && evidence.getTime().getStartDate().equals(actualTime.getStartDate())
                && evidence.getTime().getEndDate().equals(actualTime.getEndDate())
                && expectedLimit.equals(String.valueOf(requirements.getRequiredLimit()))
                && rankFilters.size() == 1
                && expectedRank.getField().equals(rankFilters.get(0).getField())
                && "LTE".equals(rankFilters.get(0).getOperator())
                && expectedLimit.equals(rankFilters.get(0).getValue());
        if (valid) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "province_wide_institution_ranking_mismatch: a whole-population which-bank ranking "
                        + "requires action=EXECUTE, intent=RANKING, organizationCodes=[], one "
                        + "recognized metric, the explicit date, exactly one "
                        + expectedRank.getField() + "/LTE/" + expectedLimit
                        + " filter, and requiredLimit=" + expectedLimit + "; model action="
                        + requirements.getAction() + ", intent=" + requirements.getIntent()
                        + ", metrics=" + safeList(requirements.getMetricCodes())
                        + ", organizations=" + safeList(requirements.getOrganizationCodes())
                        + ", time=" + actualTime + ", filters=" + filters + ", requiredLimit="
                        + requirements.getRequiredLimit()
                        + ". Regenerate the complete requirements JSON; do not select an arbitrary "
                        + "organization for a question asking which bank in the population.");
    }

    private boolean isProvinceWideInstitutionRankingQuestion(String queryText) {
        return queryText != null && containsAny(queryText, "哪家", "哪一", "哪个", "谁")
                && containsAny(queryText, "农商行", "银行", "机构") && containsAny(queryText, "排名", "排第",
                        "第一", "最后", "倒数", "最高", "最低", "最多", "最少", "最大", "最小")
                && !queryText.contains("全省均值");
    }

    /** Validates an equality/gap composition contract without changing model output. */
    private void validateStructureEquality(String errorCode, BankRequestContract requirements,
            BankIntentResult evidence, List<String> expectedMetrics) {
        List<String> actualMetrics = safeList(requirements.getMetricCodes());
        List<String> actualOrganizations = safeList(requirements.getOrganizationCodes());
        List<BankQueryPlan.DerivedMetric> derivedMetrics =
                safeList(requirements.getDerivedMetrics());
        BankQueryPlan.TimeRange actualTime = requirements.getTime();
        List<BankRequestContract.AnswerFactType> answerFacts =
                safeList(requirements.getAnswerFactTypes());
        List<BankRequestContract.AnswerFactType> expectedFacts =
                List.of(BankRequestContract.AnswerFactType.VALUE,
                        BankRequestContract.AnswerFactType.GAP_VALUE);
        String expectedOrganization = evidence.getOrganizations().get(0).getCode();
        boolean valid = requirements.getAction() == BankRequestContract.Action.EXECUTE
                && requirements.getIntent() == BankIntentType.POINT_QUERY
                && expectedMetrics.equals(actualMetrics)
                && actualOrganizations.equals(List.of(expectedOrganization))
                && derivedMetrics.isEmpty() && actualTime != null
                && evidence.getTime().getStartDate().equals(actualTime.getStartDate())
                && evidence.getTime().getEndDate().equals(actualTime.getEndDate())
                && actualTime.getGranularity() == BankQueryPlan.TimeGranularity.DAY
                && actualTime.getComparison() == BankQueryPlan.TimeComparison.NONE
                && safeList(requirements.getFilters()).isEmpty()
                && expectedFacts.equals(answerFacts);
        if (valid) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                errorCode + ": a same-date composition equality/gap question requires "
                        + "action=EXECUTE, intent=POINT_QUERY, metricCodes=" + expectedMetrics
                        + ", no derived metric or filter, one recognized organization, a DAY/NONE "
                        + "time, and answerFactTypes=[VALUE,GAP_VALUE]; model action="
                        + requirements.getAction() + ", intent=" + requirements.getIntent()
                        + ", metrics=" + actualMetrics + ", organizations=" + actualOrganizations
                        + ", time=" + actualTime + ", answerFactTypes=" + answerFacts
                        + ". Regenerate the complete requirements JSON; do not use COMPARISON or "
                        + "RATIO.");
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
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

    private void validateGenericPointRatioQuery(String queryText,
            BankRequestContract requirements) {
        if (!isGenericPointRatioQuestion(queryText)) {
            return;
        }
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        if (evidence.getMetrics().size() != 2 || evidence.getOrganizations().size() != 1
                || evidence.getTime() == null || evidence.getTime().getStartDate() == null
                || evidence.getTime().getEndDate() == null) {
            return;
        }
        List<String> expectedMetricOrder = evidence.getMetrics().stream()
                .map(BankIntentResult.MetricCandidate::getCode).toList();
        validateQueryFamily("generic_point_ratio_mismatch", requirements, BankIntentType.RATIO,
                expectedMetricOrder, Set.of(), false);
    }

    private boolean isGenericPointRatioQuestion(String queryText) {
        return queryText != null && queryText.contains("占")
                && containsAny(queryText, "比重", "比例", "占比", "比率")
                && !containsAny(queryText, "分别", "各自", "构成", "结构", "排名", "排行", "趋势", "走势", "同比",
                        "环比", "较年初", "全省均值", "对比", "比较");
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
        boolean metricOrderMatches =
                allowEquivalentMetricOrder || expectedMetricOrder.equals(actualMetricOrder);
        if (requirements.getIntent() == expectedIntent && metricOrderMatches && missing.isEmpty()
                && unexpected.isEmpty() && derivedMissing.isEmpty()
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

    private void validateExpectedIntent(String errorCode, BankRequestContract requirements,
            BankIntentType expectedIntent) {
        if (requirements.getIntent() == expectedIntent) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                errorCode + ": expected intent=" + expectedIntent + " but model intent="
                        + requirements.getIntent()
                        + ". Regenerate the complete requirements JSON and preserve every "
                        + "explicit metric, organization, date, and requested answer fact.");
    }

    private void validateProvinceAverageComparison(String queryText,
            BankRequestContract requirements) {
        BankIntentResult evidence =
                clarificationEvidenceRecognizer.recognize(queryText, LocalDate.now());
        Set<String> expectedMetrics =
                evidence.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualMetrics = new LinkedHashSet<>(requirements.getMetricCodes());
        boolean supportedIntent = requirements.getIntent() == BankIntentType.AGGREGATION
                || requirements.getIntent() == BankIntentType.COMPARISON
                        && actualMetrics.size() > 1;
        boolean hasBenchmark = requirements.getFilters().stream()
                .anyMatch(filter -> "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()));
        if (supportedIntent && hasBenchmark && !expectedMetrics.isEmpty()
                && expectedMetrics.equals(actualMetrics)) {
            return;
        }
        throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                "province_average_comparison_mismatch: expected intent=AGGREGATION or "
                        + "multi-metric COMPARISON, metricCodes=" + expectedMetrics
                        + ", and benchmark/COMPARE/PROVINCE_AVERAGE; model intent="
                        + requirements.getIntent() + ", metricCodes=" + actualMetrics
                        + ", benchmarkPresent=" + hasBenchmark
                        + ". Regenerate the complete requirements JSON from the catalog; do not "
                        + "drop an explicitly named metric or change the comparison family.");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEndpointChangeDirectionQuery(String queryText) {
        return queryText.contains("从") && queryText.contains("到")
                && containsAny(queryText, "变动方向", "变化方向") && !containsAny(queryText, "逐日", "逐月",
                        "逐季", "逐季度", "各日", "各月", "各季度", "趋势", "走势", "序列");
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
        List<String> repairCodes = new ArrayList<>();
        String previous = firstCandidate;
        BankQueryPlanParseException lastError = firstError;
        for (int repair = 1; repair <= 2; repair++) {
            try {
                String repaired =
                        prefixCache.generate(model, config, BankPlanLlmPrefixCache.Stage.PLAN,
                                BankPlanPromptComposer.buildPlanRepairUserContent(
                                        llmReq.getQueryText(), requirementsJson, previous,
                                        lastError.getMessage()),
                                false);
                previous = repaired;
                candidates.add(candidateRanker.evaluate(
                        parseAndValidatePlan(llmReq.getQueryText(), repaired, planHints),
                        planHints));
                return new PlanRepairAttempt(candidates, previous, lastError, null, repairCodes);
            } catch (BankQueryPlanParseException exception) {
                lastError = exception;
                repairCodes.add(repairErrorCode(exception));
                logRepair("PLAN", candidateIndex * 2 + repair, repairErrorCode(exception),
                        exception);
                candidates.add(BankPlanCandidateRanker.Candidate.rejected(
                        "rejected-repair-" + candidateIndex + "-" + repair,
                        exception.getReason().name()));
            } catch (RuntimeException exception) {
                return new PlanRepairAttempt(candidates, previous, lastError, exception,
                        repairCodes);
            }
        }
        return new PlanRepairAttempt(candidates, previous, lastError, null, repairCodes);
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
        if (containsAny(queryText, "加起来", "合计", "总和") && plan.getOrganizations() != null
                && plan.getOrganizations().size() > 1 && (plan.getDimensions() == null
                        || !plan.getDimensions().contains("bank_organization"))) {
            throw new BankQueryPlanParseException(
                    BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                    "multi_organization_total_dimension_mismatch: a total over multiple named "
                            + "organizations must retain dimensions=[\"bank_organization\"] and "
                            + "output.columns must retain bank_organization so result facts can "
                            + "prove every addend; regenerate the complete plan without pre-summing "
                            + "away organization identity.");
        }
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
            RuntimeException modelFailure, List<String> repairCodes) {}

    private record RequirementsAttempt(BankRequestContract contract, int attempts,
            List<String> repairReasons, List<String> repairCodes) {}
}
