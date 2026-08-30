package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankResultProjector;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fail-closed invariant audit over the projected bank result. Runs after
 * {@link BankResultProjectionHandler} and re-derives every assertion from the persisted
 * {@link BankResultProjector#CONTRACT_PROPERTY} only: a rank slice cardinality/continuity check,
 * an organization no-phantom check and a selected-dates range check. An assertion whose contract
 * fields are missing or empty is skipped, and an empty result
 * is an honest "no data" answer, never a violation — legitimate family outputs are therefore
 * never rejected here. A violation withholds the rows and re-enters the existing repairable
 * {@code RESULT_SEMANTIC} failure path (no new failure state).
 */
public class BankResultInvariantHandler implements ExecuteResultProcessor {

    /** Shared prefix of every invariant error code; each kind appends its own stable suffix. */
    public static final String ERROR_CODE_PREFIX = "INVARIANT_VIOLATION";
    static final String RANK_VIOLATION_CODE = "INVARIANT_VIOLATION_RANK";
    static final String ORGANIZATION_VIOLATION_CODE = "INVARIANT_VIOLATION_ORG";
    static final String DATE_VIOLATION_CODE = "INVARIANT_VIOLATION_DATE";

    private static final String ORG_COLUMN = "org_code";
    private static final String DATE_COLUMN = "data_date";
    private static final String RANK_COLUMN = "rank_position";
    private static final String METRIC_COLUMN = "metric_code";

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

    /**
     * Returns true when the projected result satisfies every contract-derivable invariant;
     * false when a violation was rejected into the repairable result-semantic failure state.
     */
    public boolean apply(QueryResult queryResult) {
        if (queryResult == null || queryResult.getChatContext() == null) {
            return true;
        }
        BankResultProjector.Contract contract = contract(queryResult.getChatContext());
        if (contract == null) {
            return true;
        }
        BankPlanToolResult toolResult = toolResult(queryResult.getChatContext());
        if (toolResult != null && toolResult.getStatus() == BankPlanToolResult.Status.FAILED) {
            return true;
        }
        List<Map<String, Object>> rows = mapRows(queryResult.getQueryResults());
        if (rows.isEmpty()) {
            // An empty result is honest (the data does not exist); emptiness is not an invariant.
            return true;
        }
        Set<String> columns = projectedColumns(queryResult, rows);
        Invariant violation = organizationInvariant(contract, columns, rows);
        if (violation == null) {
            violation = dateInvariant(contract, columns, rows);
        }
        if (violation == null) {
            violation = rankInvariant(contract, columns, rows);
        }
        if (violation == null) {
            return true;
        }
        return reject(queryResult, violation);
    }

    /** Organization no-phantom: projected org values must stay inside the selected set. */
    private Invariant organizationInvariant(BankResultProjector.Contract contract,
            Set<String> columns, List<Map<String, Object>> rows) {
        List<String> selected = contract.getSelectedOrganizationCodes();
        if (selected == null || selected.isEmpty() || !columns.contains(ORG_COLUMN)) {
            return null;
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String code : selected) {
            if (code != null) {
                allowed.add(code.strip());
            }
        }
        Set<String> unknown = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            Value org = cell(row, ORG_COLUMN);
            if (!org.present()) {
                continue;
            }
            if (org.value() == null || !allowed.contains(String.valueOf(org.value()).strip())) {
                unknown.add(String.valueOf(org.value()));
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return new Invariant(ORGANIZATION_VIOLATION_CODE,
                List.of("结果机构不在契约机构范围内: " + new ArrayList<>(unknown)));
    }

    /** Selected-dates range: projected time values must stay inside the contract date set. */
    private Invariant dateInvariant(BankResultProjector.Contract contract,
            Set<String> columns, List<Map<String, Object>> rows) {
        List<String> selectedDates = contract.getSelectedDates();
        if (selectedDates == null || selectedDates.isEmpty()
                || !columns.contains(DATE_COLUMN)) {
            return null;
        }
        Set<String> unknown = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            Value date = cell(row, DATE_COLUMN);
            if (!date.present()) {
                continue;
            }
            String value = date.value() == null ? null : String.valueOf(date.value()).strip();
            if (value == null || !selectedDates.contains(value)) {
                unknown.add(String.valueOf(date.value()));
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return new Invariant(DATE_VIOLATION_CODE,
                List.of("结果日期不在契约日期范围内: " + new ArrayList<>(unknown)));
    }

    /**
     * Rank slice cardinality and continuity for ranking-family projections. Ties legitimately
     * duplicate a rank value, so cardinality counts distinct ranks and continuity is only
     * asserted when the emitted slice is provably complete (top-only slice, no rank dedup
     * filtering, no duplicate ranks in the output).
     */
    private Invariant rankInvariant(BankResultProjector.Contract contract,
            Set<String> columns, List<Map<String, Object>> rows) {
        Integer topRankLimit = contract.getTopRankLimit();
        Integer bottomRankLimit = contract.getBottomRankLimit();
        if (topRankLimit == null && bottomRankLimit == null
                || !columns.contains(RANK_COLUMN)) {
            return null;
        }
        boolean projectorComputedRanks =
                contract.getType() == BankResultProjector.ProjectionType.RANKED_LONG_FORM
                        || contract.getType() == BankResultProjector.ProjectionType.DAILY_AVERAGE_RANKING;
        int rankLimit = (topRankLimit == null ? 0 : topRankLimit)
                + (bottomRankLimit == null ? 0 : bottomRankLimit);
        for (List<Map<String, Object>> group : groupByMetric(rows).values()) {
            List<Integer> ranks = new ArrayList<>();
            Set<String> invalid = new TreeSet<>();
            for (Map<String, Object> row : group) {
                Value rank = cell(row, RANK_COLUMN);
                Integer parsed = rank.present() && rank.value() != null
                        ? positiveInteger(rank.value()) : null;
                if (parsed == null) {
                    invalid.add(String.valueOf(rank.present() ? rank.value() : null));
                } else {
                    ranks.add(parsed);
                }
            }
            if (!invalid.isEmpty()) {
                return new Invariant(RANK_VIOLATION_CODE,
                        List.of("rank_position 存在非正整数值: " + new ArrayList<>(invalid)));
            }
            if (!projectorComputedRanks) {
                continue;
            }
            if (topRankLimit != null && bottomRankLimit == null) {
                int maxRank = ranks.stream().mapToInt(Integer::intValue).max().orElse(0);
                if (maxRank > topRankLimit) {
                    return new Invariant(RANK_VIOLATION_CODE, List.of(
                            "rank_position 最大值 " + maxRank + " 超出 topRankLimit " + topRankLimit));
                }
            }
            long distinctRanks = ranks.stream().distinct().count();
            if (distinctRanks > rankLimit) {
                return new Invariant(RANK_VIOLATION_CODE, List.of(
                        "rank_position 去重个数 " + distinctRanks + " 超出契约名次上限 " + rankLimit));
            }
            if (topRankLimit == null || bottomRankLimit != null) {
                continue;
            }
            List<String> selected = contract.getSelectedOrganizationCodes();
            // Any organization subset means the emitted rows are a filtered view of the full
            // ranking; contiguity is only provable for the unfiltered full ranking.
            boolean noEmitFiltering = selected == null || selected.isEmpty();
            Set<Integer> uniqueRanks = new TreeSet<>(ranks);
            if (noEmitFiltering && uniqueRanks.size() == ranks.size()
                    && (uniqueRanks.isEmpty() || uniqueRanks.stream().min(Integer::compare)
                            .get() != 1 || uniqueRanks.stream().max(Integer::compare)
                                    .get() != ranks.size())) {
                return new Invariant(RANK_VIOLATION_CODE, List.of(
                        "rank_position 未从 1 开始连续无断档: " + new ArrayList<>(uniqueRanks)));
            }
        }
        return null;
    }

    private Map<String, List<Map<String, Object>>> groupByMetric(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Value metric = cell(row, METRIC_COLUMN);
            String key = metric.present() && metric.value() != null
                    ? normalize(String.valueOf(metric.value())) : "";
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    /** Fail-closed rejection mirroring the projection handler's contract-mismatch refusal. */
    private boolean reject(QueryResult queryResult, Invariant violation) {
        failResultSemantic(queryResult.getChatContext(), violation);
        queryResult.setQueryState(QueryState.SEARCH_EXCEPTION);
        queryResult.setErrorMsg("结果不变量校验失败，已拒绝返回: " + String.join("；", violation.differences()));
        queryResult.setQueryColumns(Collections.emptyList());
        queryResult.setQueryResults(Collections.emptyList());
        queryResult.setTextResult("");
        return false;
    }

    private void failResultSemantic(SemanticParseInfo parseInfo, Invariant violation) {
        BankPlanToolResult toolResult = toolResult(parseInfo);
        if (toolResult == null || toolResult.getStatus() == BankPlanToolResult.Status.FAILED) {
            return;
        }
        toolResult.fail(BankPlanToolResult.Stage.RESULT_SEMANTIC, violation.errorCode(),
                Map.of(), violation.differences());
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY, toolResult);
    }

    private List<Map<String, Object>> mapRows(List<?> rows) {
        if (rows == null) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                maps.add(copyRow(map));
            }
        }
        return maps;
    }

    private Map<String, Object> copyRow(Map<?, ?> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : row.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private Set<String> projectedColumns(QueryResult queryResult,
            List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        if (queryResult.getQueryColumns() != null) {
            for (QueryColumn column : queryResult.getQueryColumns()) {
                if (column != null && column.getName() != null) {
                    columns.add(normalize(column.getName()));
                }
            }
        }
        for (Map<String, Object> row : rows) {
            row.keySet().forEach(key -> columns.add(normalize(key)));
        }
        return columns;
    }

    private Value cell(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getKey(), key)) {
                return new Value(true, entry.getValue());
            }
        }
        return new Value(false, null);
    }

    private Integer positiveInteger(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value).strip());
            return parsed >= 1 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
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

    private record Value(boolean present, Object value) {}

    private record Invariant(String errorCode, List<String> differences) {}
}
