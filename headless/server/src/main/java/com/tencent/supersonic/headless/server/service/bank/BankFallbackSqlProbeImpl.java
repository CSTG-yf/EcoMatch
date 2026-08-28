package com.tencent.supersonic.headless.server.service.bank;

import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFallbackSqlProbe;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server-side {@link BankFallbackSqlProbe}: translates the whitelisted free-SQL candidate with the
 * same semantic translator used for real execution, then trial-executes the physical SQL read-only
 * through the same {@link SemanticLayerService#queryByReq} path (non-trusted SQL safety policy) as
 * {@code SqlExecutor}, capped at a few rows. Translation failures, execution errors, and result
 * columns are reported back so the chat-side fallback budget can repair or fail closed.
 */
@Component
@Slf4j
public class BankFallbackSqlProbeImpl implements BankFallbackSqlProbe {

    /** The gate only needs proof of executability and column identity, not the full result. */
    static final int PROBE_ROW_LIMIT = 5;

    private static final int MAX_MESSAGE_LENGTH = 300;
    private static final String PROBE_ALIAS = "BANK_FALLBACK_PROBE";
    private static final Pattern SELECT_OR_WITH_PREFIX = Pattern.compile("^(?is)\\s*(SELECT|WITH)\\b");
    private static final Pattern SELECT_PREFIX = Pattern.compile("^(?is)\\s*SELECT\\b");

    private final SemanticLayerService semanticLayerService;

    public BankFallbackSqlProbeImpl(SemanticLayerService semanticLayerService) {
        this.semanticLayerService = semanticLayerService;
    }

    @Override
    public ProbeReport probe(LLMReq llmReq, String s2sql) {
        Long dataSetId = llmReq != null && llmReq.getSchema() != null
                ? llmReq.getSchema().getDataSetId() : null;
        // Same normalization the whitelist applies: one statement, trailing separator tolerated.
        String sql = stripStatementSeparator(s2sql);
        ProbeReport shapeViolation = shapeViolation(dataSetId, sql);
        if (shapeViolation != null) {
            return shapeViolation;
        }
        User probeUser = User.getDefaultUser();
        SemanticTranslateResp translated;
        try {
            translated = semanticLayerService.translate(sqlRequest(sql, dataSetId, null), probeUser);
        } catch (Exception e) {
            return ProbeReport.fail(ERROR_TRANSLATE_FAILED, truncate(exceptionMessage(e)));
        }
        if (translated == null || !translated.isOk()
                || StringUtils.isBlank(translated.getQuerySQL())) {
            return ProbeReport.fail(ERROR_TRANSLATE_FAILED, truncate(translated == null
                    ? "translation returned no result" : translated.getErrMsg()));
        }
        SemanticQueryResp response;
        try {
            response = semanticLayerService.queryByReq(
                    sqlRequest(sql, dataSetId, wrapWithRowCap(translated.getQuerySQL())), probeUser);
        } catch (Exception e) {
            return ProbeReport.fail(ERROR_EXECUTION_FAILED, truncate(exceptionMessage(e)));
        }
        if (response == null) {
            return ProbeReport.fail(ERROR_EXECUTION_FAILED, "trial execution returned no response");
        }
        if (StringUtils.isNotBlank(response.getErrorMsg())) {
            return ProbeReport.fail(ERROR_EXECUTION_FAILED, truncate(executionMessage(response)));
        }
        return ProbeReport.pass(resultColumns(response), resultRowCount(response));
    }

    /** Double insurance in front of the whitelist: only a single read-only statement is probed. */
    private ProbeReport shapeViolation(Long dataSetId, String sql) {
        if (dataSetId == null || dataSetId <= 0) {
            return ProbeReport.fail(ERROR_OTHER, "probe requires a dataSetId on the LLM schema");
        }
        if (!SELECT_OR_WITH_PREFIX.matcher(sql).find() || hasEmbeddedStatementSeparator(sql)) {
            return ProbeReport.fail(ERROR_OTHER, "probe only allows a single SELECT/WITH statement");
        }
        return null;
    }

    private static QuerySqlReq sqlRequest(String sql, Long dataSetId, String physicalSql) {
        QuerySqlReq request = new QuerySqlReq();
        request.setSql(sql);
        request.setDataSetId(dataSetId);
        // Server-side read-only diagnostic probe: no user authorization/masking scope is needed,
        // and the candidate must take the non-trusted SQL safety path exactly like the real
        // execution (trustedCompiledSql=false).
        request.setNeedAuth(false);
        request.setTrustedCompiledSql(false);
        if (physicalSql != null) {
            // Same bypass SqlExecutor uses for physical SQL: the statement is consumed
            // pre-translated, still through the non-trusted safety policy.
            SqlInfo sqlInfo = new SqlInfo();
            sqlInfo.setCorrectedS2SQL(sql);
            sqlInfo.setQuerySQL(physicalSql);
            sqlInfo.setCorrectedQuerySQL(physicalSql);
            request.setSqlInfo(sqlInfo);
        }
        return request;
    }

    /**
     * Caps the trial rows: plain SELECTs are wrapped in a 5-row derived table (works whether or
     * not the translated SQL already carries a LIMIT); WITH statements keep their structure and
     * only get a LIMIT appended when none is present (the translator's result limit otherwise
     * already caps them).
     */
    static String wrapWithRowCap(String physicalSql) {
        String sql = stripStatementSeparator(physicalSql);
        if (SELECT_PREFIX.matcher(sql).find()) {
            return "SELECT * FROM (" + sql + ") " + PROBE_ALIAS + " LIMIT " + PROBE_ROW_LIMIT;
        }
        return Boolean.TRUE.equals(SqlSelectHelper.hasLimit(sql)) ? sql
                : sql + " LIMIT " + PROBE_ROW_LIMIT;
    }

    private static String stripStatementSeparator(String sql) {
        String stripped = StringUtils.defaultString(sql).strip();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return stripped;
    }

    private static boolean hasEmbeddedStatementSeparator(String sql) {
        return sql.indexOf(';') >= 0;
    }

    private static List<String> resultColumns(SemanticQueryResp response) {
        if (response.getColumns() != null && !response.getColumns().isEmpty()) {
            return response.getColumns().stream().map(QueryColumn::getName).toList();
        }
        List<Map<String, Object>> rows = response.getResultList();
        if (rows != null && !rows.isEmpty()) {
            return List.copyOf(rows.get(0).keySet());
        }
        return List.of();
    }

    private static int resultRowCount(SemanticQueryResp response) {
        return response.getResultList() == null ? 0 : response.getResultList().size();
    }

    private static String executionMessage(SemanticQueryResp response) {
        Object failureLayer = response.getExecutionTelemetry() == null ? null
                : response.getExecutionTelemetry().get("failureLayer");
        String message = StringUtils.defaultIfBlank(response.getErrorMsg(), "trial execution failed");
        return failureLayer == null ? message
                : message + " (failureLayer=" + failureLayer + ")";
    }

    private static String exceptionMessage(Exception e) {
        return e.getClass().getSimpleName() + ": " + StringUtils.defaultString(e.getMessage());
    }

    private static String truncate(String message) {
        return StringUtils.abbreviate(StringUtils.normalizeSpace(StringUtils
                .defaultIfBlank(message, "unknown error")), MAX_MESSAGE_LENGTH);
    }
}
