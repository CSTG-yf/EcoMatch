package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Converts a validated bank query plan into the controlled S2SQL candidate consumed by the existing
 * mapper, parser, translator, and executor pipeline.
 */
@Service
public class BankNl2SqlExecutionCoordinator {

    private final BankQueryPlanCompiler compiler;
    private final Function<QueryStructReq, String> structS2SqlRenderer;

    public BankNl2SqlExecutionCoordinator() {
        this(new BankQueryPlanCompiler(), request -> request.convert(true).getSql());
    }

    BankNl2SqlExecutionCoordinator(BankQueryPlanCompiler compiler,
            Function<QueryStructReq, String> structS2SqlRenderer) {
        this.compiler = compiler;
        this.structS2SqlRenderer = structS2SqlRenderer;
    }

    public ExecutionCandidate coordinate(LLMReq request, LLMResp response) {
        if (request == null || response == null || response.getBankQueryPlan() == null) {
            throw new BankPlanCompilationException(BankPlanCompilationException.Reason.INVALID_PLAN,
                    "a validated bank query plan is required for execution");
        }
        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(response.getBankQueryPlan(),
                request.getSemanticIntentHints(), request.getSchema());
        String s2sql = switch (compiled.getRoute()) {
            case STRUCT -> structS2SqlRenderer.apply(compiled.getStructReq());
            case S2SQL_TEMPLATE -> compiled.getS2sql();
        };
        if (StringUtils.isBlank(s2sql)) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.S2SQL_RENDER_FAILED,
                    "the bank query plan did not produce executable S2SQL");
        }
        return new ExecutionCandidate(compiled, s2sql, response.getBankQueryPlan(), request);
    }

    @Getter
    public static final class ExecutionCandidate {
        private final BankQueryPlanCompiler.CompilationRoute route;
        private final String s2sql;
        private final List<String> outputColumns;
        private final BankResultProjector.Contract resultContract;
        private final String fingerprint;
        private final Map<String, Object> bankTelemetry;
        private final BankPlanToolResult toolResult;
        private final BankQueryPlan plan;
        private final BankRequestContract requirements;

        private ExecutionCandidate(BankQueryPlanCompiler.CompiledQuery compiled, String s2sql,
                BankQueryPlan plan, LLMReq request) {
            this.route = compiled.getRoute();
            this.s2sql = s2sql;
            this.outputColumns = compiled.getOutputColumns();
            this.resultContract = compiled.getResultContract();
            this.fingerprint = compiled.getFingerprint();
            this.bankTelemetry = bankTelemetry(plan, route);
            this.plan = plan;
            this.requirements = request.getBankRequestContract();
            BankPlanToolResult previous = request.getBankPlanToolResult();
            int attempt = previous == null ? 1 : previous.getAttempt() + 1;
            String traceId = previous == null ? UUID.randomUUID().toString()
                    : previous.getTraceId();
            this.toolResult = BankPlanToolResult.started(attempt, traceId, fingerprint,
                    route.name(), outputColumns);
        }

        public Map<String, Object> diagnostics() {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("bank.nl2sql.route", route.name());
            diagnostics.put("bank.nl2sql.fingerprint", fingerprint);
            diagnostics.put("bank.nl2sql.outputColumns", outputColumns);
            diagnostics.put("bank.nl2sql.candidateCount", 1);
            diagnostics.put("bankTelemetry", bankTelemetry);
            diagnostics.put(BankPlanToolResult.PROPERTY_KEY, toolResult);
            diagnostics.put(BankPlanToolResult.PLAN_PROPERTY_KEY, plan);
            if (requirements != null) {
                diagnostics.put(BankRequestContract.PROPERTY_KEY,
                        requirements);
            }
            if (resultContract != null) {
                diagnostics.put(BankResultProjector.CONTRACT_PROPERTY, resultContract);
            }
            return diagnostics;
        }

        private static Map<String, Object> bankTelemetry(BankQueryPlan plan,
                BankQueryPlanCompiler.CompilationRoute route) {
            Map<String, Object> telemetry = new LinkedHashMap<>();
            telemetry.put("generator", "BANK_CONSTRAINED_PLAN");
            telemetry.put("planIntent", plan.getIntent().name());
            telemetry.put("timeComparison", plan.getTime().getComparison().name());
            telemetry.put("calculationType", plan.getCalculation().getType().name());
            telemetry.put("route", route.name());
            telemetry.put("templateCategory", templateCategory(plan, route));
            return Map.copyOf(telemetry);
        }

        private static String templateCategory(BankQueryPlan plan,
                BankQueryPlanCompiler.CompilationRoute route) {
            if (route == BankQueryPlanCompiler.CompilationRoute.STRUCT) {
                return "STRUCT";
            }
            if (plan.getCalculation().getType() != BankQueryPlan.CalculationType.CHANGE) {
                return "OTHER_S2SQL_TEMPLATE";
            }
            return plan.getTime().getComparison() == BankQueryPlan.TimeComparison.MOM_AND_YOY
                    ? "MONTH_AND_YEAR_CHANGE"
                    : "CHANGE";
        }
    }
}
