package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.response.MultiTurnContextResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.Order;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Restores a bounded structured context from persisted chat queries. */
public class MultiTurnContextEngine {

    public static final int MAX_ROUNDS = 10;
    public static final int MAX_SUMMARY_LENGTH = 8_000;
    public static final int MAX_TABLE_ROWS = 10;
    public static final int MAX_TABLE_CELL_LENGTH = 24;
    public static final int MAX_TABLE_LENGTH = 1_200;
    public static final int MAX_TURN_SUMMARY_LENGTH = 300;
    public static final String STATE_SUCCESS = "SUCCESS";
    public static final String STATE_FAILED = "FAILED";
    public static final String FAILED_ERROR_NOTE = "该轮查询未能生成结果，请换一种表述方式重新提问。";
    public static final Duration CONTEXT_TTL = Duration.ofMinutes(30);

    public MultiTurnContextResp build(List<QueryResp> history, long chatId, String currentQuestion,
            Instant now) {
        MultiTurnContextResp context = new MultiTurnContextResp();
        context.setMaxRounds(MAX_ROUNDS);
        context.setOperation(detectOperation(currentQuestion));
        if ("RESET".equals(context.getOperation())) {
            return context;
        }

        List<QueryResp> candidates = history == null ? new ArrayList<>()
                : history.stream().filter(query -> belongsToChat(query, chatId))
                        .filter(this::isContextEligible).toList();
        Instant expiresAfter = now.minus(CONTEXT_TTL);
        List<QueryResp> active = candidates.stream()
                .filter(query -> query.getCreateTime() != null
                        && query.getCreateTime().toInstant().isAfter(expiresAfter))
                .sorted(Comparator.comparing(QueryResp::getCreateTime).reversed()).limit(MAX_ROUNDS)
                .sorted(Comparator.comparing(QueryResp::getCreateTime)).toList();
        context.setExpired(!candidates.isEmpty() && active.isEmpty());
        for (QueryResp query : active) {
            context.getTurns().add(isSuccessful(query) ? successTurn(query) : failedTurn(query));
            context.getSourceQueryIds().add(query.getQuestionId());
        }
        enforceSummaryLimit(context);
        context.setUsedRounds(context.getTurns().size());
        return context;
    }

    public String summarize(MultiTurnContextResp context) {
        return JsonUtil.toString(context.getTurns());
    }

    public String detectOperation(String question) {
        String normalized = StringUtils.defaultString(question).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "清空上下文", "重新开始", "新问题", "不参考之前")) {
            return "RESET";
        }
        if (containsAny(normalized, "去掉", "排除", "不要", "取消")) {
            return "REMOVE";
        }
        if (containsAny(normalized, "换成", "改成", "改为", "改查")) {
            return "REPLACE";
        }
        if (containsAny(normalized, "继续下钻", "下钻到", "细分到", "展开到")) {
            return "DRILL_DOWN";
        }
        return "APPEND";
    }

    private boolean isSuccessful(QueryResp query) {
        return query != null && query.getQueryResult() != null
                && query.getQueryResult().getQueryState() == QueryState.SUCCESS
                && CollectionUtils.isNotEmpty(query.getParseInfos());
    }

    private boolean isContextEligible(QueryResp query) {
        return query != null && query.getQueryResult() != null
                && isTerminalState(query.getQueryResult().getQueryState());
    }

    private boolean isTerminalState(QueryState state) {
        return state == QueryState.SUCCESS || state == QueryState.SEARCH_EXCEPTION
                || state == QueryState.EMPTY || state == QueryState.INVALID;
    }

    private MultiTurnContextResp.Turn successTurn(QueryResp query) {
        SemanticParseInfo parseInfo = query.getParseInfos().get(0);
        return MultiTurnContextResp.Turn.builder()
                .queryId(query.getQuestionId()).question(query.getQueryText())
                .state(STATE_SUCCESS)
                .s2sql(parseInfo.getSqlInfo() == null ? null
                        : parseInfo.getSqlInfo().getCorrectedS2SQL())
                .metrics(parseInfo.getMetrics().stream().map(this::elementName).toList())
                .dimensions(parseInfo.getDimensions().stream().map(this::elementName).toList())
                .filters(filters(parseInfo))
                .dateInfo(parseInfo.getDateInfo() == null ? null
                        : parseInfo.getDateInfo().toString())
                .orders(parseInfo.getOrders().stream().map(Order::toString).toList())
                .granularity(parseInfo.getQueryType() == null ? null
                        : parseInfo.getQueryType().name())
                .resultTable(renderResultTable(query.getQueryResult()))
                .textSummary(renderTextSummary(query.getQueryResult()))
                .build();
    }

    private MultiTurnContextResp.Turn failedTurn(QueryResp query) {
        return MultiTurnContextResp.Turn.builder()
                .queryId(query.getQuestionId()).question(query.getQueryText())
                .state(STATE_FAILED).errorNote(FAILED_ERROR_NOTE)
                .build();
    }

    private boolean belongsToChat(QueryResp query, long chatId) {
        return query != null && query.getChatId() != null && query.getChatId() == chatId;
    }

    private String elementName(SchemaElement element) {
        return StringUtils.defaultIfBlank(element.getBizName(), element.getName());
    }

    private List<String> filters(SemanticParseInfo parseInfo) {
        List<String> filters = new ArrayList<>();
        parseInfo.getDimensionFilters().stream().map(this::filter).forEach(filters::add);
        parseInfo.getMetricFilters().stream().map(this::filter).forEach(filters::add);
        return filters;
    }

    private String filter(QueryFilter filter) {
        return StringUtils.defaultIfBlank(filter.getBizName(), filter.getName()) + " "
                + filter.getOperator().getValue() + " " + Objects.toString(filter.getValue(), "");
    }

    private String renderResultTable(QueryResult result) {
        if (result == null || CollectionUtils.isEmpty(result.getQueryColumns())
                || CollectionUtils.isEmpty(result.getQueryResults())) {
            return null;
        }
        StringBuilder table = new StringBuilder();
        table.append(result.getQueryColumns().stream()
                .map(column -> StringUtils.defaultIfBlank(column.getName(), ""))
                .map(this::truncateCell).collect(Collectors.joining("|")));
        int rows = 0;
        for (Map<String, Object> row : result.getQueryResults()) {
            if (rows >= MAX_TABLE_ROWS || table.length() >= MAX_TABLE_LENGTH) {
                table.append("\n...");
                break;
            }
            table.append("\n");
            table.append(result.getQueryColumns().stream()
                    .map(column -> Objects.toString(row.get(column.getName()), ""))
                    .map(this::truncateCell).collect(Collectors.joining("|")));
            rows++;
        }
        return table.length() > MAX_TABLE_LENGTH
                ? table.substring(0, MAX_TABLE_LENGTH)
                : table.toString();
    }

    private String renderTextSummary(QueryResult result) {
        if (result == null || StringUtils.isBlank(result.getTextSummary())) {
            return null;
        }
        String summary = result.getTextSummary().trim();
        return summary.length() > MAX_TURN_SUMMARY_LENGTH
                ? summary.substring(0, MAX_TURN_SUMMARY_LENGTH)
                : summary;
    }

    private String truncateCell(String value) {
        return value.length() > MAX_TABLE_CELL_LENGTH
                ? value.substring(0, MAX_TABLE_CELL_LENGTH)
                : value;
    }

    private void enforceSummaryLimit(MultiTurnContextResp context) {
        while (context.getTurns().size() > 1 && summarize(context).length() > MAX_SUMMARY_LENGTH) {
            context.getTurns().remove(0);
            context.getSourceQueryIds().remove(0);
            context.setTruncated(true);
        }
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
