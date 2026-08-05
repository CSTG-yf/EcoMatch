package com.tencent.supersonic.headless.core.executor;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.gateway.QueryRejectedException;
import com.tencent.supersonic.headless.core.gateway.SqlPolicyViolationException;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.utils.ComponentFactory;
import com.tencent.supersonic.headless.core.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component("JdbcExecutor")
@Slf4j
public class JdbcExecutor implements QueryExecutor {

    @Value("${s2.source.result-limit:1000000}")
    private int resultLimit = 1_000_000;

    @Override
    public boolean accept(QueryStatement queryStatement) {
        return true;
    }

    @Override
    public SemanticQueryResp execute(QueryStatement queryStatement) {
        String sql = StringUtils.normalizeSpace(queryStatement.getSql());
        log.info("executing SQL [{}]", SensitiveLogUtils.summarize(sql));
        SemanticQueryResp queryResultWithColumns = new SemanticQueryResp();
        try {
            QueryExecutionGateway gateway = ContextUtils.getBean(QueryExecutionGateway.class);
            SemanticQueryResp result = queryStatement.isTrustedCompiledSql()
                    ? gateway.executeTrustedCompiledSql(queryStatement.getSql(),
                            () -> enforceResultLimit(
                                    executeInternal(queryStatement, queryResultWithColumns),
                                    resultLimit))
                    : gateway.execute(queryStatement.getSql(),
                            () -> enforceResultLimit(
                                    executeInternal(queryStatement, queryResultWithColumns),
                                    resultLimit));
            result.setSql(sql);
            return result;
        } catch (Exception e) {
            Map<String, Object> telemetry = executionTelemetry(e);
            log.error("Query execution failed: failureLayer={}", telemetry.get("failureLayer"));
            queryResultWithColumns.setExecutionTelemetry(telemetry);
            queryResultWithColumns.setErrorMsg(safeErrorMessage(e));
        }
        return queryResultWithColumns;
    }

    static Map<String, Object> executionTelemetry(Exception exception) {
        return Map.of("failureLayer", executionFailureLayer(exception));
    }

    private static String executionFailureLayer(Exception exception) {
        if (exception instanceof SqlPolicyViolationException) {
            return "SQL_SAFETY_POLICY";
        }
        if (exception instanceof QueryRejectedException) {
            return "QUERY_GATEWAY";
        }
        if (exception instanceof BadSqlGrammarException) {
            return "JDBC_GRAMMAR";
        }
        if (exception instanceof DataAccessException) {
            return "JDBC_DATA_ACCESS";
        }
        return "JDBC_OTHER";
    }

    static String safeErrorMessage(Exception exception) {
        if (exception instanceof QueryRejectedException
                || exception instanceof SqlPolicyViolationException) {
            return exception.getMessage();
        }
        return "Query execution failed";
    }

    static SemanticQueryResp enforceResultLimit(SemanticQueryResp response, int resultLimit) {
        if (response != null && response.getResultList() != null && resultLimit > 0
                && response.getResultList().size() > resultLimit) {
            throw new QueryRejectedException("Query result row limit exceeded: " + resultLimit);
        }
        return response;
    }

    private SemanticQueryResp executeInternal(QueryStatement queryStatement,
            SemanticQueryResp queryResultWithColumns) {
        for (QueryAccelerator queryAccelerator : ComponentFactory.getQueryAccelerators()) {
            if (queryAccelerator.check(queryStatement)) {
                SemanticQueryResp accelerated = queryAccelerator.query(queryStatement);
                if (Objects.nonNull(accelerated) && accelerated.getResultList() != null
                        && !accelerated.getResultList().isEmpty()) {
                    log.info("query by Accelerator {}",
                            queryAccelerator.getClass().getSimpleName());
                    return accelerated;
                }
            }
        }

        SqlUtils sqlUtils = ContextUtils.getBean(SqlUtils.class);
        DatabaseResp database = queryStatement.getOntology().getDatabase();
        sqlUtils.init(database).queryInternal(queryStatement.getSql(), queryResultWithColumns);
        return queryResultWithColumns;
    }
}
