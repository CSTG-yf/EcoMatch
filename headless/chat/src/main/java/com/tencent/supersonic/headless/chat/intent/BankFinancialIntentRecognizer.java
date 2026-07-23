package com.tencent.supersonic.headless.chat.intent;

import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon.MetricDefinition;
import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon.OrganizationDefinition;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult.Clarification;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult.FilterSlot;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult.IntentCandidate;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult.MetricCandidate;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult.OrganizationSlot;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult.TimeSlot;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BankFinancialIntentRecognizer {

    public static final double CLARIFICATION_THRESHOLD = 0.75D;

    private static final Pattern ISO_DATE =
            Pattern.compile("(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})");
    private static final Pattern CHINESE_DATE =
            Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern MONTH_END = Pattern.compile("(20\\d{2})年(\\d{1,2})月(?:末|底)");
    private static final Pattern YEAR_END = Pattern.compile("(20\\d{2})年(?:末|底|年末|年底)");
    private static final Pattern FULL_YEAR = Pattern.compile("(20\\d{2})年(?:全年|年度)");
    private static final Pattern QUARTER_END = Pattern.compile("(20\\d{2})年?(?:第)?([一二三四1-4])季度末?");
    private static final Pattern HALF_YEAR_END = Pattern.compile("(20\\d{2})年([上下])半年末");
    private static final Pattern THRESHOLD =
            Pattern.compile("(不低于|不高于|至少|至多|超过|高于|大于|低于|小于)(\\d+(?:\\.\\d+)?%?)");
    private static final Pattern PROVINCE_SCOPE = Pattern.compile("全省|13家|十三家|哪家|各家|所有(?:机构|农商行)");
    private static final Pattern BROAD_METRIC = Pattern.compile("(贷款|存款|经营|风险)(?:情况|指标|表现|怎么样|如何)");

    public BankIntentResult recognize(String queryText, LocalDate referenceDate) {
        if (StringUtils.isBlank(queryText)) {
            return emptyResult(queryText);
        }
        LocalDate effectiveReference = referenceDate == null ? LocalDate.now() : referenceDate;
        String original = queryText.trim();
        String normalized = normalize(original);

        BankIntentResult result = new BankIntentResult();
        result.setOriginalText(original);
        result.setNormalizedText(normalized);
        result.setMetrics(extractMetrics(original));
        result.setOrganizations(extractOrganizations(original));
        result.setTime(extractTime(normalized, effectiveReference));
        result.setFilters(extractFilters(normalized));
        result.setIntentCandidates(classify(normalized, result.getOrganizations().size()));
        result.setIntent(result.getIntentCandidates().get(0).getIntent());
        result.setScene(scene(result.getMetrics()));
        addClarifications(result, normalized);
        result.setConfidence(confidence(result, normalized));
        if (result.getConfidence() < CLARIFICATION_THRESHOLD
                && result.getClarifications().isEmpty()) {
            result.getClarifications().add(clarification("CONFIDENCE", "请确认本次查询口径是否正确",
                    List.of("按当前识别结果查询", "重新选择指标和条件"), "综合识别置信度低于阈值"));
        }
        result.setClarificationRequired(!result.getClarifications().isEmpty());
        result.getReasons().add("意图依据: " + result.getIntentCandidates().get(0).getReason());
        result.getReasons().add("识别指标 " + result.getMetrics().size() + " 个，机构 "
                + result.getOrganizations().size() + " 个");
        return result;
    }

    public String normalize(String queryText) {
        if (queryText == null) {
            return "";
        }
        String normalized = queryText.trim().replaceAll("[\\s\\u3000]+", " ");
        List<Map.Entry<String, String>> replacements =
                new ArrayList<>(BankFinancialLexicon.normalizations().entrySet());
        replacements.sort(Comparator.comparingInt(entry -> -entry.getKey().length()));
        for (Map.Entry<String, String> replacement : replacements) {
            if (normalized.contains(replacement.getValue())) {
                continue;
            }
            normalized = normalized.replace(replacement.getKey(), replacement.getValue());
        }
        for (OrganizationDefinition organization : BankFinancialLexicon.organizations().values()) {
            if (normalized.contains(organization.getName())) {
                continue;
            }
            for (String alias : organization.getAliases()) {
                if (!alias.equals(organization.getName())) {
                    normalized = normalized.replace(alias, organization.getName());
                }
            }
        }
        return normalized;
    }

    private List<MetricCandidate> extractMetrics(String text) {
        List<AliasHit> hits = new ArrayList<>();
        for (MetricDefinition metric : BankFinancialLexicon.metrics().values()) {
            for (String alias : metric.getAliases()) {
                int fromIndex = 0;
                while (fromIndex < text.length()) {
                    int start = text.indexOf(alias, fromIndex);
                    if (start < 0) {
                        break;
                    }
                    hits.add(new AliasHit(start, start + alias.length(), alias, metric));
                    fromIndex = start + Math.max(1, alias.length());
                }
            }
        }
        hits.sort(Comparator.comparingInt(AliasHit::length).reversed()
                .thenComparingInt(AliasHit::start));
        List<int[]> occupied = new ArrayList<>();
        Map<String, MetricCandidate> matches = new LinkedHashMap<>();
        for (AliasHit hit : hits) {
            if (overlaps(hit, occupied)) {
                continue;
            }
            occupied.add(new int[] {hit.start(), hit.end()});
            double score = hit.alias().equals(hit.metric().getName()) ? 1D
                    : hit.alias().length() >= 4 ? 0.96D : 0.88D;
            matches.putIfAbsent(hit.metric().getCode(),
                    MetricCandidate.builder().code(hit.metric().getCode())
                            .name(hit.metric().getName()).matchedText(hit.alias()).confidence(score)
                            .reason(hit.alias().equals(hit.metric().getName()) ? "标准指标名称精确匹配"
                                    : "银行指标简称或别名匹配")
                            .build());
        }
        addCompositeMetrics(text, matches);
        return new ArrayList<>(matches.values());
    }

    private void addCompositeMetrics(String text, Map<String, MetricCandidate> matches) {
        if (text.contains("净利润率")) {
            addMetric(matches, "ZB011", "净利润率分子");
            addMetric(matches, "ZB009", "净利润率分母");
        }
        if (text.contains("存贷比")) {
            addMetric(matches, "ZB002", "存贷比贷款口径");
            addMetric(matches, "ZB001", "存贷比存款口径");
        }
        if (text.contains("风险指标")) {
            for (String code : List.of("ZB013", "ZB015", "ZB017", "ZB016")) {
                addMetric(matches, code, "风险指标组合口径");
            }
        }
    }

    private void addMetric(Map<String, MetricCandidate> matches, String code, String reason) {
        MetricDefinition metric = BankFinancialLexicon.metrics().get(code);
        matches.putIfAbsent(code, MetricCandidate.builder().code(code).name(metric.getName())
                .matchedText(metric.getName()).confidence(0.94D).reason(reason).build());
    }

    private List<OrganizationSlot> extractOrganizations(String text) {
        List<OrganizationSlot> result = new ArrayList<>();
        for (OrganizationDefinition organization : BankFinancialLexicon.organizations().values()) {
            String matched = organization.getAliases().stream().filter(text::contains)
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            if (matched != null) {
                result.add(OrganizationSlot.builder().code(organization.getCode())
                        .name(organization.getName()).matchedText(matched)
                        .confidence(matched.equals(organization.getName()) ? 1D : 0.95D).build());
            }
        }
        return result;
    }

    private List<IntentCandidate> classify(String text, int organizationCount) {
        Map<BankIntentType, ScoredReason> scores = new EnumMap<>(BankIntentType.class);
        score(scores, BankIntentType.POINT_QUERY, 0.72D, "默认单点指标查询");
        if (containsAny(text, "趋势", "走势", "逐月", "逐日", "每天", "连续", "全年变化")) {
            score(scores, BankIntentType.TREND, 0.99D, "命中趋势或时间序列表达");
        }
        if (containsAny(text, "环比", "同比", "较年初", "较上季", "较上月", "较同期", "增幅", "增量", "增长", "下降", "变动",
                "变化", "从") && containsAny(text, "到", "比", "较", "变化", "增长", "下降", "变动")) {
            score(scores, BankIntentType.CHANGE, 0.98D, "命中期间变化或衍生比较表达");
        }
        if (containsAny(text, "占比", "比例", "比重", "除以", "存贷比", "净利润率")) {
            score(scores, BankIntentType.RATIO, 0.98D, "命中比率计算表达");
        }
        if (containsAny(text, "超过", "高于", "大于", "低于", "小于", "不低于", "不高于", "达标", "满足", "监管要求")) {
            score(scores, BankIntentType.THRESHOLD, 0.99D, "命中阈值或监管条件表达");
        }
        if (containsAny(text, "排名", "第几", "第一", "最后", "最高", "最低", "最多", "最少", "前三", "后三", "后四",
                "表现较好", "表现较差")) {
            score(scores, BankIntentType.RANKING, 0.98D, "命中排名或极值表达");
        }
        if (containsAny(text, "平均", "均值", "合计", "总和", "加起来", "多少家", "有几家", "多少天")) {
            score(scores, BankIntentType.AGGREGATION, 0.91D, "命中聚合统计表达");
        }
        if ((organizationCount >= 2 && containsAny(text, "谁", "更", "比", "差"))
                || containsAny(text, "两家相比", "机构间比较")) {
            score(scores, BankIntentType.COMPARISON, 0.97D, "命中多机构横向比较表达");
        }
        return scores.entrySet().stream()
                .sorted((left,
                        right) -> Double.compare(right.getValue().score(), left.getValue().score()))
                .limit(3)
                .map(entry -> IntentCandidate.builder().intent(entry.getKey())
                        .confidence(entry.getValue().score()).reason(entry.getValue().reason())
                        .build())
                .collect(Collectors.toList());
    }

    private TimeSlot extractTime(String text, LocalDate referenceDate) {
        List<DateHit> hits = new ArrayList<>();
        collectDates(text, ISO_DATE, hits, matcher -> date(matcher, 1, 2, 3), "DAY");
        collectDates(text, CHINESE_DATE, hits, matcher -> date(matcher, 1, 2, 3), "DAY");
        collectDates(text, MONTH_END, hits,
                matcher -> YearMonth.of(integer(matcher, 1), integer(matcher, 2)).atEndOfMonth(),
                "MONTH");
        collectDates(text, YEAR_END, hits, matcher -> LocalDate.of(integer(matcher, 1), 12, 31),
                "YEAR");
        collectRanges(text, FULL_YEAR, hits, matcher -> {
            int year = integer(matcher, 1);
            return new LocalDate[] {LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
        }, "YEAR");
        collectDates(text, QUARTER_END, hits, matcher -> {
            int quarter = quarter(matcher.group(2));
            return YearMonth.of(integer(matcher, 1), quarter * 3).atEndOfMonth();
        }, "QUARTER");
        collectDates(text, HALF_YEAR_END, hits, matcher -> LocalDate.of(integer(matcher, 1),
                "上".equals(matcher.group(2)) ? 6 : 12, "上".equals(matcher.group(2)) ? 30 : 31),
                "HALF_YEAR");

        if (hits.stream().findFirst().isPresent() && text.contains("年初")) {
            int year = hits.get(0).start().getYear();
            hits.add(new DateHit("年初", LocalDate.of(year, 1, 1), LocalDate.of(year, 1, 1), "YEAR"));
        }
        if (hits.isEmpty()) {
            TimeSlot relative = relativeTime(text, referenceDate);
            if (relative != null) {
                return relative;
            }
            return null;
        }
        LocalDate start = hits.stream().map(DateHit::start).min(LocalDate::compareTo).orElse(null);
        LocalDate end = hits.stream().map(DateHit::end).max(LocalDate::compareTo).orElse(null);
        String expression = hits.stream().map(DateHit::expression).distinct()
                .collect(Collectors.joining(" 至 "));
        String granularity =
                hits.size() > 1 || !start.equals(end) ? "RANGE" : hits.get(0).granularity();
        return TimeSlot.builder().expression(expression).startDate(start).endDate(end)
                .granularity(granularity).ambiguous(false).build();
    }

    private TimeSlot relativeTime(String text, LocalDate referenceDate) {
        if (containsAny(text, "最近", "近期", "近来")) {
            return TimeSlot.builder().expression("最近").granularity("UNKNOWN").ambiguous(true)
                    .build();
        }
        if (text.contains("今天")) {
            return time("今天", referenceDate, referenceDate, "DAY");
        }
        if (text.contains("本月")) {
            YearMonth month = YearMonth.from(referenceDate);
            return time("本月", month.atDay(1), month.atEndOfMonth(), "MONTH");
        }
        if (text.contains("上月")) {
            YearMonth month = YearMonth.from(referenceDate).minusMonths(1);
            return time("上月", month.atDay(1), month.atEndOfMonth(), "MONTH");
        }
        if (text.contains("今年")) {
            return time("今年", LocalDate.of(referenceDate.getYear(), 1, 1), referenceDate, "YEAR");
        }
        if (text.contains("去年")) {
            int year = referenceDate.getYear() - 1;
            return time("去年", LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), "YEAR");
        }
        if (containsAny(text, "当前", "现在")) {
            return time("当前", referenceDate, referenceDate, "DAY");
        }
        return null;
    }

    private List<FilterSlot> extractFilters(String text) {
        List<FilterSlot> filters = new ArrayList<>();
        Matcher threshold = THRESHOLD.matcher(text);
        while (threshold.find()) {
            filters.add(FilterSlot.builder().field("metric_value")
                    .operator(operator(threshold.group(1))).value(threshold.group(2))
                    .sourceText(threshold.group()).build());
        }
        if (containsAny(text, "全省平均", "全省均值", "平均水平")) {
            filters.add(FilterSlot.builder().field("benchmark").operator("COMPARE")
                    .value("PROVINCE_AVERAGE").sourceText("全省均值").build());
        }
        if (containsAny(text, "前三", "表现较好")) {
            filters.add(FilterSlot.builder().field("rank").operator("LTE").value("3")
                    .sourceText("前三").build());
        }
        if (containsAny(text, "后四", "表现较差")) {
            filters.add(FilterSlot.builder().field("rank_from_bottom").operator("LTE").value("4")
                    .sourceText("后四").build());
        }
        return filters;
    }

    private void addClarifications(BankIntentResult result, String text) {
        boolean broadMetric = BROAD_METRIC.matcher(text).find();
        if (result.getMetrics().isEmpty() || broadMetric) {
            List<String> options;
            if (text.contains("贷款")) {
                options = metricNames("ZB002", "ZB013", "ZB014", "ZB017");
            } else if (text.contains("存款")) {
                options = metricNames("ZB001", "ZB003", "ZB004");
            } else if (text.contains("风险")) {
                options = metricNames("ZB013", "ZB015", "ZB016", "ZB017");
            } else {
                options = List.of("经营规模", "盈利能力", "风险质量", "客户与渠道");
            }
            result.getClarifications().add(clarification("METRIC", "您希望查询哪个指标？", options,
                    broadMetric ? "问题使用了宽泛指标表达" : "未识别到明确指标"));
        }
        if (result.getTime() == null) {
            result.getClarifications().add(clarification("TIME", "请选择查询时间范围",
                    List.of("最新数据", "月末", "季末", "年末"), "未识别到时间条件"));
        } else if (result.getTime().isAmbiguous()) {
            result.getClarifications().add(clarification("TIME", "“最近”具体指多长时间？",
                    List.of("最近7天", "最近30天", "最近3个月"), "模糊时间无法确定起止日期"));
        }
        if (result.getOrganizations().isEmpty() && !PROVINCE_SCOPE.matcher(text).find()) {
            result.getClarifications().add(clarification("ORGANIZATION", "请选择查询机构范围",
                    List.of("全省13家农商行", "指定一家机构", "选择多家机构"), "未识别到机构范围"));
        }
        if (containsAny(text, "大不大", "怎么样", "如何") && !containsAny(text, "超过", "低于", "高于", "排名")) {
            result.getClarifications().add(clarification("STATISTICAL_CONDITION", "请选择判断口径",
                    List.of("返回原始值", "与全省均值比较", "与监管阈值比较"), "判断条件缺少基准"));
        }
        if (containsAny(text, "还是", "或者", "怎么分析") && result.getIntentCandidates().size() > 1
                && result.getIntentCandidates().get(0).getConfidence()
                        - result.getIntentCandidates().get(1).getConfidence() < 0.03D) {
            result.getClarifications().add(clarification(
                    "INTENT", "请选择希望执行的分析方式", result.getIntentCandidates().stream()
                            .map(item -> item.getIntent().name()).collect(Collectors.toList()),
                    "存在置信度接近的意图候选"));
        }
    }

    private double confidence(BankIntentResult result, String text) {
        double intent = result.getIntentCandidates().get(0).getConfidence();
        double metric = result.getMetrics().stream().mapToDouble(MetricCandidate::getConfidence)
                .max().orElse(0.35D);
        double organization =
                !result.getOrganizations().isEmpty() || PROVINCE_SCOPE.matcher(text).find() ? 0.96D
                        : 0.48D;
        double time =
                result.getTime() == null ? 0.45D : result.getTime().isAmbiguous() ? 0.4D : 0.97D;
        double score = intent * 0.30D + metric * 0.30D + organization * 0.20D + time * 0.20D;
        if (BROAD_METRIC.matcher(text).find()) {
            score = Math.min(score, 0.64D);
        }
        return round(score);
    }

    private BankBusinessScene scene(List<MetricCandidate> metrics) {
        Set<String> codes =
                metrics.stream().map(MetricCandidate::getCode).collect(Collectors.toSet());
        if (codes.stream().anyMatch(code -> code.matches("ZB01[3-7]"))) {
            return BankBusinessScene.RISK_CONTROL;
        }
        if (codes.contains("ZB020") || codes.contains("ZB021")) {
            return BankBusinessScene.CUSTOMER_MARKETING;
        }
        return BankBusinessScene.OPERATION_ANALYSIS;
    }

    private BankIntentResult emptyResult(String queryText) {
        BankIntentResult result = new BankIntentResult();
        result.setOriginalText(queryText);
        result.setNormalizedText("");
        result.setScene(BankBusinessScene.OPERATION_ANALYSIS);
        result.setIntent(BankIntentType.UNKNOWN);
        result.setConfidence(0D);
        result.setClarificationRequired(true);
        result.getIntentCandidates().add(IntentCandidate.builder().intent(BankIntentType.UNKNOWN)
                .confidence(0D).reason("问题为空").build());
        result.getClarifications().add(clarification("QUERY", "请输入需要查询的银行业务问题",
                List.of("查询指标", "机构比较", "趋势分析"), "问题文本为空"));
        return result;
    }

    private void collectDates(String text, Pattern pattern, List<DateHit> hits,
            DateExtractor extractor, String granularity) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                LocalDate value = extractor.extract(matcher);
                hits.add(new DateHit(matcher.group(), value, value, granularity));
            } catch (RuntimeException ignored) {
                // Invalid date text remains unmatched and will lower confidence through
                // clarification.
            }
        }
    }

    private void collectRanges(String text, Pattern pattern, List<DateHit> hits,
            RangeExtractor extractor, String granularity) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            LocalDate[] range = extractor.extract(matcher);
            hits.add(new DateHit(matcher.group(), range[0], range[1], granularity));
        }
    }

    private boolean overlaps(AliasHit hit, List<int[]> occupied) {
        return occupied.stream().anyMatch(range -> hit.start() < range[1] && hit.end() > range[0]);
    }

    private List<String> metricNames(String... codes) {
        List<String> names = new ArrayList<>();
        for (String code : codes) {
            names.add(BankFinancialLexicon.metrics().get(code).getName());
        }
        return names;
    }

    private void score(Map<BankIntentType, ScoredReason> scores, BankIntentType intent,
            double score, String reason) {
        ScoredReason current = scores.get(intent);
        if (current == null || score > current.score()) {
            scores.put(intent, new ScoredReason(score, reason));
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private LocalDate date(Matcher matcher, int year, int month, int day) {
        return LocalDate.of(integer(matcher, year), integer(matcher, month), integer(matcher, day));
    }

    private int integer(Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private int quarter(String value) {
        return switch (value) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            default -> 4;
        };
    }

    private String operator(String source) {
        return switch (source) {
            case "超过", "高于", "大于" -> "GT";
            case "低于", "小于" -> "LT";
            case "不低于", "至少" -> "GTE";
            default -> "LTE";
        };
    }

    private TimeSlot time(String expression, LocalDate start, LocalDate end, String granularity) {
        return TimeSlot.builder().expression(expression).startDate(start).endDate(end)
                .granularity(granularity).ambiguous(false).build();
    }

    private Clarification clarification(String type, String question, List<String> options,
            String reason) {
        return Clarification.builder().type(type).question(question).options(options).reason(reason)
                .build();
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private record AliasHit(int start, int end, String alias, MetricDefinition metric) {
        int length() {
            return end - start;
        }
    }

    private record DateHit(String expression, LocalDate start, LocalDate end, String granularity) {}

    private record ScoredReason(double score, String reason) {}

    @FunctionalInterface
    private interface DateExtractor {
        LocalDate extract(Matcher matcher);
    }

    @FunctionalInterface
    private interface RangeExtractor {
        LocalDate[] extract(Matcher matcher);
    }
}
