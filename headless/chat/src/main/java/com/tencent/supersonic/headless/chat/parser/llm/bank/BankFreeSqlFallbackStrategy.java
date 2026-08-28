package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFreeSqlPromptComposer.FreeSqlResponse;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFreeSqlWhitelistValidator.Catalog;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.provider.ModelProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Controlled free-SQL fallback for semantic-unreachable bank questions (design v1 §2).
 *
 * <p>Budget is strictly one generation plus one repair round with deterministic error feedback
 * (whitelist violations or publish-gate probe failures). The model output is a structured dual
 * response ({sql, columns, confidence}); the SQL must pass
 * {@link BankFreeSqlWhitelistValidator} before it may leave this class. When a
 * {@link BankFallbackSqlProbe} bean is available, the whitelisted candidate is additionally
 * trial-executed (read-only, tiny row cap) before publication so that execution failures and
 * missing declared columns become repairable feedback instead of terminal execute-stage errors;
 * without a probe bean the gate is skipped and behavior is unchanged. Execution of published
 * candidates goes through the non-trusted SqlSafetyPolicy path and the QueryExecutionGateway, and
 * the declared columns are fail-closed against the physical result metadata by the FREE
 * projection contract.
 */
@Service
@Slf4j
public class BankFreeSqlFallbackStrategy {

    public static final String APP_KEY = "BANK_FREE_SQL_FALLBACK";

    /** Diagnostics property values (eval side reads planSource and bankTelemetry.generator). */
    public static final String PLAN_SOURCE = "FREE_SQL";
    public static final String PLAN_SOURCE_PROPERTY = "bank.nl2sql.planSource";

    private static final Logger KEY_PIPELINE_LOG = LoggerFactory.getLogger("keyPipeline");

    /** One generation plus at most one whitelist-repair round (design v1 §2⑥). */
    private static final int MAX_MODEL_ATTEMPTS = 2;

    /** Repair-round header for candidates that failed the whitelist (or were malformed). */
    private static final String WHITELIST_REPAIR_HEADER =
            "上一轮输出未通过白名单校验，必须修正以下全部问题后重新输出：";

    /** Repair-round header for candidates that passed the whitelist but failed the probe gate. */
    private static final String PROBE_REPAIR_HEADER =
            "上一轮输出已通过白名单校验，但发布前试执行未通过（试执行失败或声明列缺失），"
                    + "必须修正以下全部问题后重新输出：";

    /** Logged once when no probe bean exists so gate-skip stays visible without spamming. */
    private static volatile boolean probeAbsentLogged;

    /**
     * Explicit decode bound for the {sql, columns} JSON: remote providers apply their own default
     * output cap when max_tokens is absent, which truncates longer fallback SQL. The bound also
     * covers the plan channel's observed P99 output maxima with headroom.
     */
    private static final int FREE_FALLBACK_MAX_OUTPUT_TOKENS = 2048;

    private final ConcurrentHashMap<String, FixedSystemPrefixLlmCache> fallbackPrefixCaches =
            new ConcurrentHashMap<>();

    public BankFreeSqlFallbackStrategy() {
        ChatAppManager.register(APP_KEY,
                ChatApp.builder().name("银行受控自由SQL兜底").description(
                                "主路径查询族不可达时的白名单约束自由 SQL 兜底（默认关闭）")
                        .enable(false).appModule(AppModule.CHAT).build());
    }

    @Getter
    public static final class FallbackSql {
        private final String sql;
        private final List<String> declaredColumns;
        private final Double confidence;
        private final int modelAttempts;
        private final String triggerReason;

        FallbackSql(String sql, List<String> declaredColumns, Double confidence,
                int modelAttempts, String triggerReason) {
            this.sql = sql;
            this.declaredColumns = List.copyOf(declaredColumns);
            this.confidence = confidence;
            this.modelAttempts = modelAttempts;
            this.triggerReason = triggerReason;
        }
    }

    /**
     * Generates one whitelisted free-SQL candidate, or returns null when the budget is exhausted
     * without a valid statement (terminal, never a silent downgrade).
     */
    public FallbackSql generate(LLMReq llmReq, String triggerReason) {
        if (llmReq == null || llmReq.getSchema() == null
                || !BankFreeSqlPromptComposer.isBankSchema(llmReq.getSchema())) {
            log.warn("bank free-SQL fallback declined: bank schema is required");
            return null;
        }
        ChatModelConfig modelConfig = resolveModelConfig(llmReq);
        if (modelConfig == null) {
            log.warn("bank free-SQL fallback declined: no chat model configuration for {} or {}",
                    APP_KEY, OnePassSCSqlGenStrategy.APP_KEY);
            return null;
        }
        ChatLanguageModel model = ModelProvider.getChatModel(modelConfig);
        Catalog catalog = BankFreeSqlWhitelistValidator.catalogFromSchema(llmReq.getSchema());
        String stableSchema = BankFreeSqlPromptComposer.buildStableSchemaBlock(llmReq.getSchema());
        String systemPrefix = BankFreeSqlPromptComposer.composeFreeFallbackSystemPrefix(
                stableSchema);
        String prefixVersion = BankFreeSqlPromptComposer.freeFallbackPrefixVersion(stableSchema);
        FixedSystemPrefixLlmCache cache = fallbackPrefixCaches.computeIfAbsent(prefixVersion,
                key -> new FixedSystemPrefixLlmCache(systemPrefix, key, 256, false,
                        BankFreeSqlPromptComposer.freeSqlWarmProbe(), false, 0,
                        "FREE_FALLBACK", FREE_FALLBACK_MAX_OUTPUT_TOKENS));
        String question = StringUtils.defaultString(llmReq.getQueryText()).strip();
        String userContent = BankFreeSqlPromptComposer.buildQuestionOnlyUserContent(question, "",
                "");

        List<String> lastViolations = List.of();
        String lastRepairHeader = WHITELIST_REPAIR_HEADER;
        BankFallbackSqlProbe probe = resolveProbe();
        for (int attempt = 1; attempt <= MAX_MODEL_ATTEMPTS; attempt++) {
            String dynamicUser = attempt == 1 ? userContent
                    : buildRepairUserContent(question, lastViolations, lastRepairHeader);
            String raw = cache.generate(model, modelConfig, dynamicUser, false);
            FreeSqlResponse response = BankFreeSqlPromptComposer.parseFreeSqlResponse(raw);
            if (response == null) {
                lastViolations = List.of(
                        "模型输出不是合法 JSON：必须只输出 {\"sql\":\"...\",\"columns\":[{\"alias\":..."
                                + ",\"semantic_type\":\"...\",\"unit\":\"...\"}],\"confidence\":0.0-1.0}");
                logFallback(attempt, "MALFORMED_JSON", lastViolations);
                continue;
            }
            String sql = response.getSql() == null ? "" : response.getSql().strip();
            List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, catalog);
            if (!violations.isEmpty()) {
                // Whitelist violations are exactly the repairable class (design v1 §2⑥).
                lastViolations = violations;
                logFallback(attempt, "WHITELIST_VIOLATION", violations);
                continue;
            }
            List<String> declared = declaredAliases(response);
            List<String> declarationViolations =
                    BankFreeSqlWhitelistValidator.validateDeclaredColumns(sql, declared, catalog);
            if (!declarationViolations.isEmpty()) {
                // Dual-output mismatch fails closed (design v1 §2⑤) — no repair, no downgrade.
                KEY_PIPELINE_LOG.warn(
                        "BankFreeSqlFallbackStrategy dual-output mismatch after whitelist pass, fail closed: {}",
                        declarationViolations);
                return null;
            }
            if (probe != null) {
                String probeViolation = publishGateViolation(probe, llmReq, sql, declared);
                if (probeViolation != null) {
                    lastViolations = List.of(probeViolation);
                    lastRepairHeader = PROBE_REPAIR_HEADER;
                    logFallback(attempt, "PROBE_FAILED", lastViolations);
                    continue;
                }
            }
            KEY_PIPELINE_LOG.info(
                    "BankFreeSqlFallbackStrategy accepted free-SQL fallback attempt={} trigger={} declaredColumns={}",
                    attempt, triggerReason, declared);
            return new FallbackSql(sql, declared, response.getConfidence(),
                    attempt, triggerReason);
        }
        KEY_PIPELINE_LOG.warn(
                "BankFreeSqlFallbackStrategy budget exhausted without a whitelisted statement, trigger={}",
                triggerReason);
        return null;
    }

    /** Diagnostics for the fallback candidate parseInfo (contract, planSource, telemetry). */
    public Map<String, Object> buildDiagnostics(FallbackSql fallback,
            BankResultProjector.Contract contract) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put(PLAN_SOURCE_PROPERTY, PLAN_SOURCE);
        diagnostics.put("bank.nl2sql.freeSql.triggerReason", fallback.getTriggerReason());
        diagnostics.put("bank.nl2sql.freeSql.modelAttempts", fallback.getModelAttempts());
        diagnostics.put("bank.nl2sql.route", "FREE_SQL");
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("generator", PLAN_SOURCE);
        telemetry.put("route", "FREE_SQL");
        telemetry.put("templateCategory", "FREE_SQL");
        telemetry.put("triggerReason", fallback.getTriggerReason());
        diagnostics.put("bankTelemetry", telemetry);
        if (contract != null) {
            diagnostics.put(BankResultProjector.CONTRACT_PROPERTY, contract);
        }
        return diagnostics;
    }

    /** FREE passthrough contract carrying the declared canonical columns in declared order. */
    public BankResultProjector.Contract buildResultContract(FallbackSql fallback) {
        List<BankResultProjector.MetricBinding> bindings = new ArrayList<>();
        for (String column : fallback.getDeclaredColumns()) {
            bindings.add(BankResultProjector.MetricBinding.builder()
                    .semanticColumn(column).build());
        }
        return BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.FREE)
                .metrics(bindings)
                .build();
    }

    /** True when the parseInfo was produced by this fallback channel (non-trusted execution). */
    public static boolean isFreeSqlFallbackParse(SemanticParseInfo parseInfo) {
        return parseInfo != null && parseInfo.getProperties() != null
                && PLAN_SOURCE.equals(parseInfo.getProperties().get(PLAN_SOURCE_PROPERTY));
    }

    private static List<String> declaredAliases(FreeSqlResponse response) {
        if (response.getColumns() == null) {
            return List.of();
        }
        return response.getColumns().stream()
                .filter(column -> column != null && StringUtils.isNotBlank(column.getAlias()))
                .map(column -> column.getAlias().strip()).toList();
    }

    static String buildRepairUserContent(String question, List<String> violations) {
        return buildRepairUserContent(question, violations, WHITELIST_REPAIR_HEADER);
    }

    static String buildRepairUserContent(String question, List<String> violations, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.defaultString(question).strip());
        sb.append("\n\n").append(StringUtils.defaultIfBlank(header, WHITELIST_REPAIR_HEADER));
        int index = 1;
        for (String violation : violations) {
            sb.append("\n").append(index++).append(". ").append(violation);
        }
        sb.append("\n\n重新只输出一条符合系统规则的 JSON：{\"sql\":\"...\",\"columns\":"
                + "[{\"alias\":\"规范列名\",\"semantic_type\":\"...\",\"unit\":\"...\"}],"
                + "\"confidence\":0.0-1.0}，不要输出其他文本。");
        return sb.toString();
    }

    /**
     * Opportunistic probe lookup: the execution facilities live in headless-server, so the gate
     * only exists when a {@link BankFallbackSqlProbe} bean is present. Without Spring (unit tests,
     * bare construction) the bean lookup fails and the gate is skipped, keeping legacy behavior;
     * the skip is logged once to stay visible without spamming every request.
     */
    static BankFallbackSqlProbe resolveProbe() {
        try {
            return ContextUtils.getBean(BankFallbackSqlProbe.class);
        } catch (RuntimeException e) {
            if (!probeAbsentLogged) {
                probeAbsentLogged = true;
                KEY_PIPELINE_LOG.info(
                        "BankFreeSqlFallbackStrategy no BankFallbackSqlProbe bean available; "
                                + "pre-publish trial-execution gate skipped (behavior unchanged)");
            }
            return null;
        }
    }

    /**
     * Publish-gate check: trial-executes the whitelisted candidate and compares the physical
     * result columns against the declared ones. Returns the repairable violation text, or null
     * when the candidate may be published. A probe exception or a not-ok report is treated like a
     * whitelist violation (repairable within budget, fail-closed when the budget is exhausted).
     */
    private static String publishGateViolation(BankFallbackSqlProbe probe, LLMReq llmReq,
            String sql, List<String> declared) {
        BankFallbackSqlProbe.ProbeReport report = probeQuietly(probe, llmReq, sql);
        if (report == null) {
            return "发布前试执行未返回结果，视同试执行失败";
        }
        if (!report.ok()) {
            return describeProbeFailure(report);
        }
        List<String> missingColumns = missingDeclaredColumns(report, declared);
        if (!missingColumns.isEmpty()) {
            return "试执行结果缺少声明列: " + missingColumns
                    + "（实际返回列: " + report.resultColumns() + "）";
        }
        return null;
    }

    private static BankFallbackSqlProbe.ProbeReport probeQuietly(BankFallbackSqlProbe probe,
            LLMReq llmReq, String sql) {
        try {
            return probe.probe(llmReq, sql);
        } catch (RuntimeException e) {
            KEY_PIPELINE_LOG.warn(
                    "BankFreeSqlFallbackStrategy probe threw, treating as probe failure: {}",
                    e.getClass().getSimpleName());
            return BankFallbackSqlProbe.ProbeReport.fail(BankFallbackSqlProbe.ERROR_OTHER,
                    "probe exception: " + StringUtils.defaultString(e.getMessage()));
        }
    }

    private static String describeProbeFailure(BankFallbackSqlProbe.ProbeReport report) {
        String detail = StringUtils.defaultIfBlank(report.message(), "无失败详情");
        return "试执行失败[" + StringUtils.defaultIfBlank(report.errorCode(),
                BankFallbackSqlProbe.ERROR_OTHER) + "]: " + detail;
    }

    /** Case-insensitive containment check mirroring the FREE projection contract normalization. */
    private static List<String> missingDeclaredColumns(BankFallbackSqlProbe.ProbeReport report,
            List<String> declared) {
        Set<String> actualColumns = report.resultColumns() == null ? Set.of()
                : report.resultColumns().stream().filter(Objects::nonNull)
                        .map(column -> column.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        return declared.stream()
                .filter(column -> !actualColumns.contains(column.strip().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * The fallback channel reuses the S2SQL parser app's model binding when the dedicated app has
     * no model configured, so enabling the switch never requires new agent configuration.
     */
    private ChatModelConfig resolveModelConfig(LLMReq llmReq) {
        if (llmReq.getChatAppConfig() == null) {
            return null;
        }
        ChatApp dedicated = llmReq.getChatAppConfig().get(APP_KEY);
        if (dedicated != null && dedicated.getChatModelConfig() != null
                && StringUtils.isNotBlank(dedicated.getChatModelConfig().getBaseUrl()
                        != null ? dedicated.getChatModelConfig().getBaseUrl() : "")) {
            return dedicated.getChatModelConfig();
        }
        ChatApp s2sqlParser = llmReq.getChatAppConfig().get(OnePassSCSqlGenStrategy.APP_KEY);
        if (s2sqlParser != null && s2sqlParser.getChatModelConfig() != null) {
            return s2sqlParser.getChatModelConfig();
        }
        return dedicated != null ? dedicated.getChatModelConfig() : null;
    }

    private void logFallback(int attempt, String code, List<String> violations) {
        KEY_PIPELINE_LOG.info("BankFreeSqlFallbackStrategy attempt={} code={} violationCount={}",
                attempt, code, violations.size());
    }
}
