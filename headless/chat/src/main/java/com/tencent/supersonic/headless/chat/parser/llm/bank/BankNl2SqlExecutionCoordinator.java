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
        return new ExecutionCandidate(compiled, s2sql);
    }

    @Getter
    public static final class ExecutionCandidate {
        private final BankQueryPlanCompiler.CompilationRoute route;
        private final String s2sql;
        private final List<String> outputColumns;
        private final BankResultProjector.Contract resultContract;
        private final String fingerprint;

        private ExecutionCandidate(BankQueryPlanCompiler.CompiledQuery compiled, String s2sql) {
            this.route = compiled.getRoute();
            this.s2sql = s2sql;
            this.outputColumns = compiled.getOutputColumns();
            this.resultContract = compiled.getResultContract();
            this.fingerprint = compiled.getFingerprint();
        }

        public Map<String, Object> diagnostics() {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("bank.nl2sql.route", route.name());
            diagnostics.put("bank.nl2sql.fingerprint", fingerprint);
            diagnostics.put("bank.nl2sql.outputColumns", outputColumns);
            diagnostics.put("bank.nl2sql.candidateCount", 1);
            if (resultContract != null) {
                diagnostics.put(BankResultProjector.CONTRACT_PROPERTY, resultContract);
            }
            return diagnostics;
        }
    }
}
