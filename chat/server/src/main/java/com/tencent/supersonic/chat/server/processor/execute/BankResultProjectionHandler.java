package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.util.ResultFormatter;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankResultProjector;

import java.util.List;
import java.util.Set;

/** Applies a bank-specific presentation contract after semantic execution has completed. */
public class BankResultProjectionHandler implements ExecuteResultProcessor {

    private static final Set<String> NUMERIC_COLUMNS = Set.of("absolute_change", "absolute_gap",
            "aggregate_value", "baseline_value", "current_value", "daily_average",
            "days_above_average", "denominator_value", "deposit_per_outlet_wanyuan",
            "deposit_value", "employee_count", "gap_value", "max_value", "metric_value",
            "min_value", "net_profit", "numerator_value", "observation_count", "outlet_count",
            "per_capita_profit", "percent_change",
            "provincial_average", "quarter_change", "rank_position", "ratio_percent",
            "total_days", "value_difference");

    private final BankResultProjector projector = new BankResultProjector();

    @Override
    public boolean accept(ExecuteContext executeContext) {
        return executeContext != null && executeContext.getResponse() != null
                && executeContext.getResponse().getChatContext() != null
                && contract(executeContext.getResponse().getChatContext()) != null;
    }

    @Override
    public void process(ExecuteContext executeContext) {
        apply(executeContext.getResponse());
    }

    public boolean apply(QueryResult queryResult) {
        if (queryResult == null || queryResult.getChatContext() == null) {
            return false;
        }
        BankResultProjector.Contract contract = contract(queryResult.getChatContext());
        boolean freeSqlContract = contract != null
                && contract.getType() == BankResultProjector.ProjectionType.FREE;
        if (freeSqlContract && !BankResultProjector.freeSqlColumnsConsistent(contract,
                columnNames(queryResult))) {
            return failFreeSqlContract(queryResult);
        }
        BankResultProjector.Projection projection =
                projector.project(contract, queryResult.getQueryResults());
        if (!projection.isApplied()) {
            if (freeSqlContract) {
                return failFreeSqlContract(queryResult);
            }
            failResultSemantic(queryResult.getChatContext());
            return false;
        }
        List<QueryColumn> columns = projection.getColumns().stream().map(this::projectedColumn).toList();
        queryResult.setQueryColumns(columns);
        queryResult.setQueryResults(projection.getRows());
        queryResult.setTextResult(ResultFormatter.transform2TextNew(columns, projection.getRows()));
        completeToolResult(queryResult.getChatContext(), projection);
        return true;
    }

    /**
     * Fail-closed refusal of a fallback answer whose execution output diverges from the declared
     * canonical columns: the query becomes an explicit failure and the raw rows are withheld —
     * never a silent downgrade to the unprojected table (design v1 §2⑤).
     */
    private boolean failFreeSqlContract(QueryResult queryResult) {
        failResultSemantic(queryResult.getChatContext());
        queryResult.setQueryState(QueryState.SEARCH_EXCEPTION);
        queryResult.setErrorMsg("自由 SQL 兜底输出与声明的列契约不一致，已拒绝返回");
        queryResult.setQueryColumns(java.util.Collections.emptyList());
        queryResult.setQueryResults(java.util.Collections.emptyList());
        queryResult.setTextResult("");
        return false;
    }

    private List<String> columnNames(QueryResult queryResult) {
        if (queryResult.getQueryColumns() == null) {
            return List.of();
        }
        return queryResult.getQueryColumns().stream().map(QueryColumn::getName)
                .filter(java.util.Objects::nonNull).toList();
    }

    private QueryColumn projectedColumn(String name) {
        QueryColumn column = new QueryColumn(name, "STRING", name);
        if ("data_date".equals(name)) {
            column.setType("DATE");
            column.setShowType("DATE");
        } else if (NUMERIC_COLUMNS.contains(name)) {
            column.setType("NUMBER");
            column.setShowType("NUMBER");
        }
        return column;
    }

    private void failResultSemantic(SemanticParseInfo parseInfo) {
        BankPlanToolResult toolResult = toolResult(parseInfo);
        if (toolResult == null || toolResult.getStatus() == BankPlanToolResult.Status.FAILED) {
            return;
        }
        toolResult.fail(BankPlanToolResult.Stage.RESULT_SEMANTIC, "RESULT_CONTRACT_MISMATCH",
                java.util.Map.of(), List.of("检查输出事实、列契约和查询族是否匹配"));
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
    }

    private void completeToolResult(SemanticParseInfo parseInfo,
            BankResultProjector.Projection projection) {
        BankPlanToolResult toolResult = toolResult(parseInfo);
        if (toolResult == null || toolResult.getStatus() == BankPlanToolResult.Status.FAILED) {
            return;
        }
        toolResult.complete(projection.getColumns(), projection.getRows());
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
    }

    private BankPlanToolResult toolResult(SemanticParseInfo parseInfo) {
        return parseInfo == null ? null
                : BankPlanToolResult
                        .from(parseInfo.getProperties().get(BankPlanToolResult.PROPERTY_KEY));
    }

    private BankResultProjector.Contract contract(SemanticParseInfo parseInfo) {
        Object value = parseInfo.getProperties().get(BankResultProjector.CONTRACT_PROPERTY);
        if (value instanceof BankResultProjector.Contract contract) {
            return contract;
        }
        if (value == null) {
            return null;
        }
        try {
            return JsonUtil.toObject(JsonUtil.toString(value), BankResultProjector.Contract.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
