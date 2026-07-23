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
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component("JdbcExecutor")
@Slf4j
public class JdbcExecutor implements QueryExecutor {
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
            SemanticQueryResp result = gateway.execute(queryStatement.getSql(),
                    () -> executeInternal(queryStatement, queryResultWithColumns));
            result.setSql(sql);
            return result;
        } catch (Exception e) {
            log.error("Query execution failed: type={}, error=[{}]", e.getClass().getSimpleName(),
                    SensitiveLogUtils.summarize(e));
            queryResultWithColumns.setErrorMsg(safeErrorMessage(e));
        }
        return queryResultWithColumns;
    }

    static String safeErrorMessage(Exception exception) {
        if (exception instanceof QueryRejectedException
                || exception instanceof SqlPolicyViolationException) {
            return exception.getMessage();
        }
        return "Query execution failed";
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
