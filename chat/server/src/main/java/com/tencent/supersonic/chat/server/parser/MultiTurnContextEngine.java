package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.response.MultiTurnContextResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
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
import java.util.Objects;

/** Restores a bounded structured context from persisted successful chat queries. */
public class MultiTurnContextEngine {

    public static final int MAX_ROUNDS = 10;
    public static final int MAX_SUMMARY_LENGTH = 8_000;
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
                        .filter(this::isSuccessful).toList();
        Instant expiresAfter = now.minus(CONTEXT_TTL);
        List<QueryResp> active = candidates.stream()
                .filter(query -> query.getCreateTime() != null
                        && query.getCreateTime().toInstant().isAfter(expiresAfter))
                .sorted(Comparator.comparing(QueryResp::getCreateTime).reversed()).limit(MAX_ROUNDS)
                .sorted(Comparator.comparing(QueryResp::getCreateTime)).toList();
        context.setExpired(!candidates.isEmpty() && active.isEmpty());
        for (QueryResp query : active) {
            SemanticParseInfo parseInfo = query.getParseInfos().get(0);
            MultiTurnContextResp.Turn turn = MultiTurnContextResp.Turn.builder()
                    .queryId(query.getQuestionId()).question(query.getQueryText())
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
                    .build();
            context.getTurns().add(turn);
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
