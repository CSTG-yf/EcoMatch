package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.parser.llm.LLMResponseService;
import com.tencent.supersonic.headless.chat.parser.llm.ParseResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Terminal-state interception that routes a semantically unreachable constrained bank plan into
 * the controlled free-SQL fallback (design v1 §1/§2/§4).
 *
 * <p>Admission is a whitelist with two terminal classes: compiler terminal failures with reason
 * UNSUPPORTED_QUERY_SHAPE / UNSUPPORTED_CALCULATION / UNSUPPORTED_FILTER / S2SQL_RENDER_FAILED,
 * and plan-stage structured-repair budget exhaustion marked by
 * {@link BankNl2SqlError#isPlanStageExhausted()} (trigger reason
 * {@code PLAN_STAGE_EXHAUSTED:<failureCode>}; the failure code may be any last blocking code,
 * including MALFORMED_JSON / SCHEMA_VIOLATION / VALIDATION_FAILED). Clarification,
 * MODEL_FAILURE / ENVIRONMENT_FAULT, intermediate rounds that still own a repair chance, and the
 * disabled switch all decline, and bank-off mode never reaches the constrained route at all. The
 * hook lives in the parser layer:
 * {@code LLMSqlParser} owns the LLMReq/schema plumbing and the candidate pipeline here, while the
 * chat-server repair loop only sees persisted execution results.
 */
@Slf4j
public final class BankFreeSqlFallbackHook {

    /** Compiler reasons whose terminal failure may enter the fallback channel. */
    private static final Set<BankPlanCompilationException.Reason> ADMITTED_REASONS = Set.of(
            BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE,
            BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
            BankPlanCompilationException.Reason.UNSUPPORTED_FILTER,
            BankPlanCompilationException.Reason.S2SQL_RENDER_FAILED);

    /** Trigger-reason prefix for plan-stage structured-repair budget exhaustion. */
    static final String PLAN_STAGE_EXHAUSTED_PREFIX = "PLAN_STAGE_EXHAUSTED";

    private BankFreeSqlFallbackHook() {}

    /**
     * Machine-readable trigger reason for an admitted terminal error: the compiler reason name
     * for compile-stage failures, and {@code PLAN_STAGE_EXHAUSTED:<failureCode>} when the plan
     * strategy exhausted its structured repair budget without a valid plan. Null when the error
     * is not admitted (structural/model failures, clarification, no-candidate validation misses,
     * intermediate rounds that still own a repair chance).
     */
    static String admittedTriggerReason(BankNl2SqlError error) {
        if (error == null) {
            return null;
        }
        if (error.isPlanStageExhausted()) {
            return error.getPlanFailureCode() == null || error.getPlanFailureCode().isBlank()
                    ? PLAN_STAGE_EXHAUSTED_PREFIX
                    : PLAN_STAGE_EXHAUSTED_PREFIX + ":" + error.getPlanFailureCode();
        }
        if (error.getCategory() != BankNl2SqlError.Category.COMPILATION_FAILURE) {
            return null;
        }
        BankPlanCompilationException compilationException =
                findCompilationException(error.getCause());
        if (compilationException == null || compilationException.getReason() == null
                || !ADMITTED_REASONS.contains(compilationException.getReason())) {
            return null;
        }
        return compilationException.getReason().name();
    }

    /**
     * True when the terminal error qualifies for the fallback channel: an admitted compiler
     * reason or plan-stage budget exhaustion. Clarification, no-candidate validation misses,
     * model/environment failures, and intermediate rounds that still own a repair chance never
     * fall back — free SQL cannot help them.
     */
    static boolean admits(BankNl2SqlError error) {
        return admittedTriggerReason(error) != null;
    }

    /**
     * Runs the fallback channel for an admitted terminal error. Returns true when a whitelisted
     * candidate was added to the parse pipeline (the caller must then swallow the terminal
     * error); false when the caller must proceed with the original terminal failure.
     */
    public static boolean tryRun(ChatQueryContext queryCtx, LLMReq llmReq,
            BankNl2SqlError error) {
        if (!admits(error) || !fallbackEnabled() || !bankConstrainedRoute(llmReq)) {
            return false;
        }
        BankFreeSqlFallbackStrategy strategy;
        try {
            strategy = ContextUtils.getBean(BankFreeSqlFallbackStrategy.class);
        } catch (RuntimeException e) {
            strategy = new BankFreeSqlFallbackStrategy();
        }
        return tryRun(queryCtx, llmReq, error, strategy,
                ContextUtils.getBean(LLMResponseService.class));
    }

    static boolean tryRun(ChatQueryContext queryCtx, LLMReq llmReq, BankNl2SqlError error,
            BankFreeSqlFallbackStrategy strategy, LLMResponseService responseService) {
        String triggerReason = admittedTriggerReason(error);
        if (triggerReason == null) {
            return false;
        }
        if (!fallbackEnabled()) {
            log.info("bank free-SQL fallback disabled by switch; terminal error stands");
            return false;
        }
        if (!bankConstrainedRoute(llmReq)) {
            // bank-off mode: S2SQL_PARSER is already the unconstrained full path.
            return false;
        }
        log.info("bank free-SQL fallback triggered: reason={}", triggerReason);
        BankFreeSqlFallbackStrategy.FallbackSql fallback = strategy.generate(llmReq,
                triggerReason);
        if (fallback == null) {
            return false;
        }
        publishCandidate(queryCtx, llmReq, fallback, strategy, responseService);
        return true;
    }

    private static void publishCandidate(ChatQueryContext queryCtx, LLMReq llmReq,
            BankFreeSqlFallbackStrategy.FallbackSql fallback,
            BankFreeSqlFallbackStrategy strategy, LLMResponseService responseService) {
        BankResultProjector.Contract contract = strategy.buildResultContract(fallback);
        LLMResp fallbackResp = new LLMResp();
        fallbackResp.setQuery(llmReq.getQueryText());
        fallbackResp.setSchema(BankFreeSqlPromptComposer.buildStableSchemaBlock(llmReq.getSchema()));
        fallbackResp.setSqlOutput(fallback.getSql());
        ParseResult parseResult = ParseResult.builder()
                .dataSetId(llmReq.getSchema().getDataSetId()).llmReq(llmReq)
                .llmResp(fallbackResp).build();
        responseService.addParseInfo(queryCtx, parseResult, fallback.getSql(), 1.0D,
                strategy.buildDiagnostics(fallback, contract));
        publishFallbackTelemetry(queryCtx.getParseResp(), llmReq);
    }

    /** Overwrites the COMPILER_EXCEPTION attempt telemetry with the successful FREE_SQL route. */
    private static void publishFallbackTelemetry(ParseResp parseResp, LLMReq llmReq) {
        if (parseResp == null || llmReq == null || llmReq.getBankRoutingTelemetry() == null) {
            return;
        }
        Object enabled = llmReq.getBankRoutingTelemetry().get("bankConstrainedPlanEnabled");
        Object qualified = llmReq.getBankRoutingTelemetry().get("bankDatasetQualified");
        if (!(enabled instanceof Boolean) || !(qualified instanceof Boolean)) {
            return;
        }
        parseResp.setBankRoutingAttemptTelemetry(new ParseResp.BankRoutingAttemptTelemetry(
                (Boolean) enabled, (Boolean) qualified,
                ParseResp.BankRoutingSqlGenType.FREE_SQL, true, null, null));
    }

    private static boolean bankConstrainedRoute(LLMReq llmReq) {
        return llmReq != null
                && LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN.equals(llmReq.getSqlGenType());
    }

    static boolean fallbackEnabled() {
        try {
            ParserConfig config = ContextUtils.getBean(ParserConfig.class);
            return Boolean.parseBoolean(config.getParameterValue(
                    ParserConfig.PARSER_BANK_FREE_SQL_FALLBACK_ENABLE));
        } catch (RuntimeException e) {
            // Fail closed when no Spring context / config is available.
            return false;
        }
    }

    static BankPlanCompilationException findCompilationException(Throwable error) {
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
}
