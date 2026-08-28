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
 * <p>Admission is a whitelist: only compiler terminal failures with reason
 * UNSUPPORTED_QUERY_SHAPE / UNSUPPORTED_CALCULATION / UNSUPPORTED_FILTER / S2SQL_RENDER_FAILED
 * (repair budget exhausted) qualify. MALFORMED_JSON / SCHEMA_VIOLATION, CLARIFICATION_REQUIRED,
 * VALIDATION_FAILED, MODEL_FAILURE / ENVIRONMENT_FAULT and the disabled switch all decline, and
 * bank-off mode never reaches the constrained route at all. The hook lives in the parser layer:
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

    private BankFreeSqlFallbackHook() {}

    /**
     * True when the terminal error qualifies for the fallback channel. Structural/model failures
     * (MALFORMED_JSON, SCHEMA_VIOLATION), clarification, no-candidate validation misses and
     * model/environment failures never fall back — free SQL cannot help them.
     */
    static boolean admits(BankNl2SqlError error) {
        if (error == null || error.getCategory() != BankNl2SqlError.Category.COMPILATION_FAILURE) {
            return false;
        }
        BankPlanCompilationException compilationException =
                findCompilationException(error.getCause());
        return compilationException != null && compilationException.getReason() != null
                && ADMITTED_REASONS.contains(compilationException.getReason());
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
        if (!admits(error)) {
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
        BankPlanCompilationException compilationException = findCompilationException(
                error.getCause());
        String triggerReason = compilationException.getReason().name();
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
