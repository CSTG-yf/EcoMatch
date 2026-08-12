package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.JsonUtil;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model-owned constrained bank planning.
 *
 * <p>The first model response is a requirement contract, the second is an executable semantic
 * plan. This class intentionally contains no question keyword recognizer, business-code mapper,
 * deterministic plan template, or post-generation plan rewrite.
 */
@Service
public class BankPlanGenStrategy extends SqlGenStrategy {

    public static final String APP_KEY = "BANK_CONSTRAINED_PLAN";

    private static final Logger KEY_PIPELINE_LOG = LoggerFactory.getLogger("keyPipeline");
    private static final int MAX_REQUIREMENT_ATTEMPTS = 3;

    private final BankRequestContractResponseParser requestContractParser =
            new BankRequestContractResponseParser();
    private final BankQueryPlanResponseParser responseParser = new BankQueryPlanResponseParser();
    private final BankPlanCandidateRanker candidateRanker = new BankPlanCandidateRanker();
    private final BankPlanLlmPrefixCache prefixCache = new BankPlanLlmPrefixCache();

    public BankPlanGenStrategy() {
        ChatAppManager.register(APP_KEY,
                ChatApp.builder().name("银行受约束查询计划")
                        .description("由大模型生成经过事实目录校验的银行查询计划")
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

        BankRequestContract requirements = obtainRequirements(llmReq, model, modelConfig,
                admissionHints);
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
        int candidateLimit = toolRepair ? 1 : Math.max(1, Math.min(3, llmReq.getBankMaxCandidates()));

        List<BankPlanCandidateRanker.Candidate> candidates = new ArrayList<>();
        BankQueryPlanParseException lastPlanError = null;
        String lastCandidate = null;
        RuntimeException lastModelFailure = null;
        for (int candidateIndex = 0; candidateIndex < candidateLimit; candidateIndex++) {
            String candidate = null;
            try {
                candidate = prefixCache.generate(model, modelConfig, dynamicUser, candidateLimit == 1);
                lastCandidate = candidate;
                candidates.add(candidateRanker.evaluate(responseParser.parse(candidate, planHints),
                        planHints));
            } catch (BankQueryPlanParseException exception) {
                lastPlanError = exception;
                candidates.add(BankPlanCandidateRanker.Candidate.rejected(
                        "rejected-plan-" + candidateIndex, exception.getReason().name()));
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
            diagnostics.put("bankPlanPrefixCache", prefixCache.stats());
            KEY_PIPELINE_LOG.info(
                    "BankPlanGenStrategy selected {} unique model plan candidate(s), rejected={}",
                    selection.getUniqueCandidateCount(), selection.getRejectedCandidateCount());
            return planResponse(llmReq, requirements, selection.getSelected().getPlan(), diagnostics);
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

    private BankRequestContract obtainRequirements(LLMReq llmReq, ChatLanguageModel model,
            ChatModelConfig config, SemanticIntentHints admissionHints) {
        if (llmReq.getBankRequestContract() != null) {
            return llmReq.getBankRequestContract();
        }
        String candidate = null;
        BankQueryPlanParseException lastError = null;
        for (int attempt = 0; attempt < MAX_REQUIREMENT_ATTEMPTS; attempt++) {
            String user = attempt == 0
                    ? BankPlanPromptComposer.buildRequirementsUserContent(llmReq.getQueryText())
                    : BankPlanPromptComposer.buildRequirementsRepairUserContent(llmReq.getQueryText(),
                            candidate, lastError == null ? "requirements JSON is invalid"
                                    : lastError.getMessage());
            try {
                candidate = prefixCache.generate(model, config, user, attempt == 0);
                return requestContractParser.parse(candidate, admissionHints);
            } catch (BankQueryPlanParseException exception) {
                lastError = exception;
            } catch (RuntimeException exception) {
                throw BankNl2SqlError.modelFailure(exception);
            }
        }
        throw BankNl2SqlError.afterSingleRepair(lastError == null
                ? new BankQueryPlanParseException(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                        "model did not return an executable requirements contract")
                : lastError);
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
                                requirementsJson, previous, lastError.getMessage()), false);
                previous = repaired;
                candidates.add(candidateRanker.evaluate(responseParser.parse(repaired, planHints),
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
}
