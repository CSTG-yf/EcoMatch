package com.tencent.supersonic.chat.server.processor.execute;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankRequestContract;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankSemanticRegistry;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.provider.ModelProvider;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns verified database rows into a user-visible answer through a strict, model-owned answer
 * contract. The model may phrase the answer, but it must cite returned fact IDs. This keeps
 * natural-language generation model-led while making unsupported claims fail closed.
 */
public class BankFinalAnswerProcessor implements ExecuteResultProcessor {

    public static final String APP_KEY = "BANK_FINAL_ANSWER";
    public static final String TRACE_PROPERTY = "bank.nl2sql.finalAnswerTrace";
    static final int MAX_ATTEMPTS = 2;
    static final int MAX_ANSWER_CHARACTERS = 4_096;
    static final int MAX_RESULT_CHARACTERS = 200_000;

    /**
     * This is deliberately a fixed system contract. Agent configuration selects and configures
     * the model, but cannot silently replace the output grammar used by the validator.
     */
    public static final String INSTRUCTION =
            """
                    # 角色
                    你是银行问数 Agent 的最终回答模型。你只能依据 <result_facts> 中返回的事实回答，
                    不得补造、外推或重复查询过程。

                    # 输入含义
                    - Question：用户原始问题。
                    - Request contract：同一会话中模型已确认的用户需求；answerFactTypes 是本次答案允许且必须覆盖的事实类型。
                    - result_facts：数据库工具返回的原子事实。每条都有唯一 id、type、field、value、context。
                      当 context 含有 metric_code 时，metric_name、metric_unit、metric_direction 是该代码的权威解释；
                      必须按它们称呼指标和单位，绝不可凭记忆重映射指标代码。

                    # 必须遵守的规则
                    1. 直接回答 Question，不要复述问题、SQL、字段名、记录数、数据范围、最大最小值或推理过程，除非它们本身就在需求事实类型中。
                    2. 只能引用 result_facts 中实际存在的 factIds；每一个业务数字必须由你选择的 factIds 中的 value 支撑。
                    3. Request contract 中每个 answerFactTypes 至少选择一个同类型事实，且 factIds 只能选择这些类型；
                       不得为了补充背景选择未声明类型的 current_value、baseline_value 或 percent_change 事实。
                       若没有足够事实，不要猜测；仍按格式返回，并只选择真实事实。
                    4. TREND_DIRECTION 类型的事实是由查询结果推导的确定性趋势，回答时必须使用该事实的 value（上升、下降或持平）。
                    5. 百分比、金额、排名等可以做通常展示性四舍五入，但不得改变方向、口径或数值含义。
                    6. 当问题要求最高或最低、哪个期间或极值结论时，必须在同一句中绑定该结论的日期、数值和单位；
                       不能只在前文的数值列表中出现它们。例如：2026-03 数值最高（42.32亿元）。
                    7. 当 Request contract.time.granularity 为 MONTH 或 QUARTER 时，期间标签必须与
                       result_facts.context.data_date 表示同一期间；可沿用 YYYY-MM、YYYY-MM-DD 或中文季度末写法，
                       但不得改成结果中不存在的日期、月份或季度。
                    8. 对“高于/低于全省均值 X”这类表述，X 必须引用 field=absolute_gap 的正数事实；
                       absolute_gap 是绝对差额；gap_value 是“目标值-全省均值”的有符号差额，只有明确表述正负号时才能引用它，绝不可自行取绝对值。
                    9. 当问题要求列出、逐项或全部指标及排名时，必须为 requirements 中每个直接或派生指标
                       至少写出一个对应的值事实和排名事实；排名不在前三或后N名的指标也不能省略。
                    10. “表现较好/表现较差”只能按 Question 明确给出的名次集合判断；例如“前三”和“后四”时，
                        只有排名第1-3属于较好，只有排名大于总数-4属于较差，中间名次不得擅自归类。
                    11. 只有 factIds 中包含 TREND_DIRECTION 事实时才能写整体上升、下降或持平；不能根据首尾数值自行补写趋势。
                    12. 不要输出 Markdown、代码块、解释文字或 JSON 之外的任何字符。

                    # 唯一允许的输出格式
                    只输出一行合法 JSON，字段名和类型必须完全一致：
                    {"answer":"<直接回答>","factIds":["F1","F2"]}
                    尖括号中的“直接回答”只是占位说明，绝不可原样输出；answer 必须是基于所选 factIds 写出的自然语言答案，
                    factIds 必须替换为 result_facts 中实际存在的 id。

                    # Question
                    {{question}}
                    # Request contract
                    {{requirements}}
                    <result_facts>
                    {{result_facts}}
                    </result_facts>
                    # Previous response
                    {{previous_response}}
                    # validation_feedback
                    {{validation_feedback}}
                    """;

    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");
    private static final ObjectMapper STRICT_JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    private static final Pattern DATE = Pattern.compile("\\d{4}[-年/]\\d{1,2}(?:[-月/]\\d{1,2}日?)?");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:20[2-3]\\d)年?(?!\\d)");
    private static final Pattern CODE = Pattern.compile("(?i)(?:ORG|ZB)\\d{3}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final List<String> GENERIC_INSIGHT_MARKERS =
            List.of("问题范围：", "查询返回", "范围为", "首条记录", "末条记录", "结论仅适用于", "结果少于", "指标口径：");
    private static final List<String> TECHNICAL_FIELD_MARKERS =
            List.of("current_value", "baseline_value", "absolute_change", "percent_change",
                    "metric_value", "ratio_percent", "rank_position", "observation_count");
    private static final Set<String> IDENTITY_FIELDS = Set.of("org_code", "org_name", "metric_code",
            "metric_name", "metric_role", "data_date", "comparison_type", "bank_organization",
            "bank_indicator");

    private final AnswerGenerator answerGenerator;

    public BankFinalAnswerProcessor() {
        this((chatApp, prompt) -> {
            ChatLanguageModel model =
                    ModelProvider.getChatModel(ModelConfigHelper.getChatModelConfig(chatApp));
            return model.generate(prompt.toUserMessage()).content().text();
        });
        ChatAppManager.register(APP_KEY,
                ChatApp.builder().prompt(INSTRUCTION).name("银行问数直接回答")
                        .description("依据已验证结果事实生成可追溯的最终答案").appModule(AppModule.CHAT)
                        .enable(false).build());
    }

    BankFinalAnswerProcessor(AnswerGenerator answerGenerator) {
        this.answerGenerator = Objects.requireNonNull(answerGenerator);
    }

    @Override
    public boolean accept(ExecuteContext context) {
        if (context == null || context.getParseInfo() == null
                || context.getParseInfo().getProperties() == null) {
            return false;
        }
        if (!context.getParseInfo().getProperties().containsKey(BankPlanToolResult.PLAN_PROPERTY_KEY)) {
            return reject(context, "FINAL_ANSWER_PLAN_MISSING");
        }
        if (requestContract(context) == null) {
            return reject(context, "FINAL_ANSWER_REQUIREMENTS_MISSING");
        }
        if (context.getResponse() == null || context.getAgent() == null) {
            return reject(context, "FINAL_ANSWER_CONTEXT_INCOMPLETE");
        }
        if (!QueryState.SUCCESS.equals(context.getResponse().getQueryState())) {
            return reject(context, "FINAL_ANSWER_QUERY_NOT_SUCCESSFUL");
        }
        if (StringUtils.isNotBlank(context.getResponse().getTextSummary())) {
            return reject(context, "FINAL_ANSWER_SUMMARY_ALREADY_PRESENT");
        }
        ChatApp chatApp = context.getAgent().getChatAppConfig() == null ? null
                : context.getAgent().getChatAppConfig().get(APP_KEY);
        if (chatApp == null || !chatApp.isEnable()) {
            return reject(context, "FINAL_ANSWER_APP_DISABLED_OR_UNCONFIGURED");
        }
        if (context.getResponse().getQueryResults() == null
                || context.getResponse().getQueryColumns() == null) {
            return reject(context, "FINAL_ANSWER_RESULT_SHAPE_MISSING");
        }
        return true;
    }

    @Override
    public void process(ExecuteContext context) {
        QueryResult result = context.getResponse();
        String question = DataInterpretProcessor.resolveQuestion(context);
        BankRequestContract requirements = requestContract(context);
        List<ResultFact> allFacts = resultFacts(result);
        List<ResultFact> facts = filterFactsByContract(allFacts, requirements);
        String resultFactsJson = JsonUtil.toString(facts);
        if (StringUtils.isBlank(question) || requirements == null || allFacts.isEmpty()
                || resultFactsJson.length() > MAX_RESULT_CHARACTERS) {
            recordTrace(context, "FAILED", 0, List.of("FINAL_ANSWER_INPUT_INVALID"), allFacts.size());
            return;
        }

        ChatApp chatApp = context.getAgent().getChatAppConfig().get(APP_KEY);
        String previousResponse = "";
        List<String> feedback = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Prompt prompt = prompt(question, requirements, resultFactsJson, previousResponse, feedback);
            final String candidate;
            try {
                candidate = StringUtils.trimToEmpty(answerGenerator.generate(chatApp, prompt));
            } catch (RuntimeException exception) {
                keyPipelineLog.warn("Bank final answer generation failed: type={}",
                        exception.getClass().getSimpleName());
                recordTrace(context, "FAILED", attempt, List.of("MODEL_UNAVAILABLE"), allFacts.size());
                return;
            }
            Validation validation = validate(candidate, requirements, allFacts);
            if (validation.valid()) {
                result.setTextSummary(validation.response().answer);
                recordTrace(context, "SUCCEEDED", attempt, List.of(), allFacts.size());
                keyPipelineLog.info("Bank final answer accepted: attempts={}, response=[{}]", attempt,
                        SensitiveLogUtils.summarize(validation.response().answer));
                return;
            }
            previousResponse = candidate;
            feedback = validation.errors();
        }
        recordTrace(context, "FAILED", MAX_ATTEMPTS, feedback, allFacts.size());
    }

    private Prompt prompt(String question, BankRequestContract requirements, String resultFacts,
            String previousResponse, List<String> feedback) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", question);
        variables.put("requirements", JsonUtil.toString(requirements));
        variables.put("result_facts", resultFacts);
        variables.put("previous_response", StringUtils.defaultIfBlank(previousResponse, "无"));
        variables.put("validation_feedback", feedback.isEmpty() ? "无"
                : String.join("；", feedback) + "。请只按唯一 JSON 格式修正。" );
        return PromptTemplate.from(INSTRUCTION).apply(variables);
    }

    private Validation validate(String candidate, BankRequestContract requirements,
            List<ResultFact> facts) {
        AnswerResponse response;
        try {
            response = parse(candidate);
        } catch (RuntimeException | JsonProcessingException exception) {
            return invalid("ANSWER_JSON_INVALID: " + safeMessage(exception));
        }
        List<String> errors = new ArrayList<>();
        String answer = StringUtils.trimToEmpty(response.answer);
        if (StringUtils.isBlank(answer)) {
            errors.add("ANSWER_EMPTY");
        }
        if (answer.length() > MAX_ANSWER_CHARACTERS) {
            errors.add("ANSWER_TOO_LONG");
        }
        if (GENERIC_INSIGHT_MARKERS.stream().anyMatch(answer::contains)) {
            errors.add("ANSWER_GENERIC_SUMMARY");
        }
        if (TECHNICAL_FIELD_MARKERS.stream().anyMatch(answer::contains)) {
            errors.add("ANSWER_TECHNICAL_FIELD");
        }
        List<String> requestedIds = response.factIds == null ? List.of() : response.factIds;
        if (requestedIds.isEmpty()) {
            errors.add("ANSWER_FACT_IDS_REQUIRED");
        }
        Set<String> uniqueIds = new LinkedHashSet<>(requestedIds);
        if (uniqueIds.size() != requestedIds.size()) {
            errors.add("ANSWER_DUPLICATE_FACT_ID");
        }
        Map<String, ResultFact> factsById = new LinkedHashMap<>();
        facts.forEach(fact -> factsById.put(fact.id(), fact));
        List<String> unknownIds = uniqueIds.stream().filter(id -> !factsById.containsKey(id)).toList();
        if (!unknownIds.isEmpty()) {
            errors.add("ANSWER_UNKNOWN_FACT_ID: " + unknownIds);
        }

        List<ResultFact> selectedFacts = uniqueIds.stream().map(factsById::get)
                .filter(Objects::nonNull).toList();
        Set<BankRequestContract.AnswerFactType> selectedTypes = selectedFacts.stream()
                .map(ResultFact::type).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<BankRequestContract.AnswerFactType> requiredTypes = requirements.getAnswerFactTypes() == null
                ? List.of() : requirements.getAnswerFactTypes();
        boolean hasTrendFact = facts.stream()
                .anyMatch(fact -> fact.type() == BankRequestContract.AnswerFactType.TREND_DIRECTION);
        boolean trendIsIndeterminate = requiredTypes.contains(
                BankRequestContract.AnswerFactType.TREND_DIRECTION)
                && !hasTrendFact && hasMultipleTrendPoints(facts);
        List<BankRequestContract.AnswerFactType> missingTypes = requiredTypes.stream()
                .filter(type -> !selectedTypes.contains(type))
                .filter(type -> type != BankRequestContract.AnswerFactType.TREND_DIRECTION
                        || !trendIsIndeterminate)
                .distinct().toList();
        if (!missingTypes.isEmpty()) {
            errors.add("ANSWER_REQUIRED_FACT_TYPES_MISSING: " + missingTypes);
        }
        List<BankRequestContract.AnswerFactType> unexpectedTypes = selectedTypes.stream()
                .filter(type -> !requiredTypes.contains(type)).toList();
        if (!unexpectedTypes.isEmpty()) {
            errors.add("ANSWER_UNREQUESTED_FACT_TYPES: " + unexpectedTypes);
        }

        boolean selectedTrendFact = selectedFacts.stream()
                .anyMatch(fact -> fact.type() == BankRequestContract.AnswerFactType.TREND_DIRECTION);
        if (!selectedTrendFact && containsTrendClaim(answer)) {
            errors.add("ANSWER_UNREQUESTED_TREND");
        }
        if (requiredTypes.contains(BankRequestContract.AnswerFactType.VALUE)
                && requiredTypes.contains(BankRequestContract.AnswerFactType.RANK)) {
            for (String metricCode : requestedMetricCodes(requirements)) {
                if (!hasMetricFact(selectedFacts, metricCode,
                        BankRequestContract.AnswerFactType.VALUE)) {
                    errors.add("ANSWER_REQUIRED_METRIC_VALUE_MISSING: " + metricCode);
                }
                if (!hasMetricFact(selectedFacts, metricCode,
                        BankRequestContract.AnswerFactType.RANK)) {
                    errors.add("ANSWER_REQUIRED_METRIC_RANK_MISSING: " + metricCode);
                }
            }
        }

        List<BigDecimal> grounded = groundedNumbers(selectedFacts);
        List<BigDecimal> ungrounded = numbers(answer).stream().filter(value -> grounded.stream()
                .noneMatch(source -> roundedEquals(source, value))).toList();
        if (!ungrounded.isEmpty()) {
            errors.add("ANSWER_UNGROUNDED_NUMBER: " + ungrounded);
        }
        selectedFacts.stream()
                .filter(fact -> fact.type() == BankRequestContract.AnswerFactType.TREND_DIRECTION)
                .filter(fact -> !answer.contains(String.valueOf(fact.value())))
                .forEach(fact -> errors.add("ANSWER_UNGROUNDED_TREND: " + fact.id()));
        return errors.isEmpty() ? new Validation(true, List.of(), response)
                : new Validation(false, List.copyOf(errors), null);
    }

    private boolean containsTrendClaim(String answer) {
        return answer.contains("上升趋势") || answer.contains("下降趋势")
                || answer.contains("整体持平") || answer.contains("总体持平")
                || answer.contains("整体上升") || answer.contains("整体下降");
    }

    private boolean hasMultipleTrendPoints(List<ResultFact> facts) {
        return facts.stream().filter(fact -> fact.type() == BankRequestContract.AnswerFactType.VALUE)
                .map(fact -> fact.context().get("data_date"))
                .filter(Objects::nonNull).map(String::valueOf).distinct().count() >= 2;
    }

    private Set<String> requestedMetricCodes(BankRequestContract requirements) {
        Set<String> codes = new LinkedHashSet<>();
        if (requirements.getMetricCodes() != null) {
            requirements.getMetricCodes().stream().filter(StringUtils::isNotBlank)
                    .map(StringUtils::upperCase).forEach(codes::add);
        }
        if (requirements.getDerivedMetrics() != null) {
            requirements.getDerivedMetrics().stream().map(BankQueryPlan.DerivedMetric::getMetricCode)
                    .filter(StringUtils::isNotBlank).map(StringUtils::upperCase).forEach(codes::add);
        }
        return codes;
    }

    private boolean hasMetricFact(List<ResultFact> facts, String metricCode,
            BankRequestContract.AnswerFactType type) {
        return facts.stream().anyMatch(fact -> fact.type() == type
                && metricCode.equalsIgnoreCase(String.valueOf(fact.context().get("metric_code"))));
    }

    private List<BigDecimal> groundedNumbers(List<ResultFact> facts) {
        List<BigDecimal> values = new ArrayList<>();
        for (ResultFact fact : facts) {
            BigDecimal value = asNumber(fact.value());
            if (value == null) {
                continue;
            }
            values.add(value);
            // A signed gap and its published absolute_gap are the same result-row fact
            // expressed with different display semantics. Permit normal "低于/高于均值 X"
            // wording to cite the signed field when the model omitted the companion ID.
            if ("gap_value".equals(fact.field()) || "absolute_gap".equals(fact.field())) {
                values.add(value.abs());
            }
        }
        return values;
    }

    private AnswerResponse parse(String candidate) throws JsonProcessingException {
        if (!StringUtils.startsWith(candidate, "{") || !StringUtils.endsWith(candidate, "}")) {
            throw new IllegalArgumentException("response must be one JSON object");
        }
        return STRICT_JSON_MAPPER.readValue(candidate, AnswerResponse.class);
    }

    private List<ResultFact> resultFacts(QueryResult result) {
        List<ResultFact> facts = new ArrayList<>();
        Map<String, List<TrendPoint>> series = new LinkedHashMap<>();
        int nextId = 1;
        for (Map<String, Object> row : result.getQueryResults()) {
            if (row == null) {
                continue;
            }
            Map<String, Object> context = identityContext(row);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                BankRequestContract.AnswerFactType type = factType(entry.getKey());
                if (type != null && entry.getValue() != null) {
                    facts.add(new ResultFact("F" + nextId++, type, entry.getKey(), entry.getValue(), context));
                }
            }
            BigDecimal metricValue = asNumber(row.get("metric_value"));
            Object dataDate = row.get("data_date");
            if (metricValue != null && dataDate != null) {
                String seriesKey = trendSeriesKey(context);
                series.computeIfAbsent(seriesKey, ignored -> new ArrayList<>())
                        .add(new TrendPoint(String.valueOf(dataDate), metricValue, context));
            }
        }
        for (List<TrendPoint> points : series.values()) {
            if (points.size() < 2) {
                continue;
            }
            points.sort(Comparator.comparing(TrendPoint::date));
            TrendPoint first = points.get(0);
            TrendPoint last = points.get(points.size() - 1);
            boolean allEqual = points.stream()
                    .allMatch(point -> point.value().compareTo(first.value()) == 0);
            if (first.value().compareTo(last.value()) == 0 && !allEqual) {
                // Equal endpoints with intermediate movement are not a "持平" trend.
                continue;
            }
            String direction = last.value().compareTo(first.value()) > 0 ? "上升"
                    : last.value().compareTo(first.value()) < 0 ? "下降" : "持平";
            Map<String, Object> context = new LinkedHashMap<>(last.context());
            context.put("start_date", first.date());
            context.put("end_date", last.date());
            context.put("start_value", first.value());
            context.put("end_value", last.value());
            facts.add(new ResultFact("F" + nextId++, BankRequestContract.AnswerFactType.TREND_DIRECTION,
                    "derived_trend_direction", direction, context));
        }
        return List.copyOf(facts);
    }

    private List<ResultFact> filterFactsByContract(List<ResultFact> facts,
            BankRequestContract requirements) {
        if (requirements == null || requirements.getAnswerFactTypes() == null
                || requirements.getAnswerFactTypes().isEmpty()) {
            return facts;
        }
        Set<BankRequestContract.AnswerFactType> allowed =
                new LinkedHashSet<>(requirements.getAnswerFactTypes());
        return facts.stream().filter(fact -> allowed.contains(fact.type())).toList();
    }

    private BankRequestContract.AnswerFactType factType(String field) {
        return switch (StringUtils.defaultString(field)) {
            case "metric_value", "aggregate_value", "current_value", "baseline_value", "daily_average",
                    "minimum_value", "maximum_value", "min_value", "max_value", "numerator_value",
                    "denominator_value", "deposit_value", "deposit_per_outlet_wanyuan" ->
                BankRequestContract.AnswerFactType.VALUE;
            case "percent_change" -> BankRequestContract.AnswerFactType.CHANGE_RATE;
            case "absolute_change", "quarter_change", "year_change" -> BankRequestContract.AnswerFactType.CHANGE_VALUE;
            case "ratio_percent" -> BankRequestContract.AnswerFactType.RATIO_VALUE;
            case "rank_position" -> BankRequestContract.AnswerFactType.RANK;
            case "provincial_average" -> BankRequestContract.AnswerFactType.PROVINCE_AVERAGE;
            case "gap_value", "absolute_gap", "value_difference" -> BankRequestContract.AnswerFactType.GAP_VALUE;
            case "days_above_average", "total_days", "observation_count", "outlet_count" -> BankRequestContract.AnswerFactType.COUNT;
            case "meets_condition" -> BankRequestContract.AnswerFactType.COMPARISON_VALUE;
            default -> null;
        };
    }

    private Map<String, Object> identityContext(Map<String, Object> row) {
        Map<String, Object> context = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (IDENTITY_FIELDS.contains(key) && value != null) {
                context.put(key, value);
            }
        });
        enrichMetricContext(context);
        return Map.copyOf(context);
    }

    private void enrichMetricContext(Map<String, Object> context) {
        Object rawMetricCode = context.get("metric_code");
        if (!(rawMetricCode instanceof String metricCode) || StringUtils.isBlank(metricCode)) {
            return;
        }
        BankSemanticRegistry.MetricDefinition definition = BankSemanticRegistry.metrics().get(metricCode);
        if (definition == null) {
            definition = BankSemanticRegistry.derivedMetrics().get(metricCode);
        }
        if (definition == null) {
            return;
        }
        context.put("metric_name", definition.name());
        context.put("metric_unit", definition.unit());
        context.put("metric_direction", definition.direction().name());
    }

    private String trendSeriesKey(Map<String, Object> context) {
        return List.of("org_code", "org_name", "metric_code", "metric_name", "metric_role",
                "comparison_type").stream().map(key -> String.valueOf(context.getOrDefault(key, "")))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private BigDecimal asNumber(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = StringUtils.trimToEmpty(Objects.toString(value, ""));
        return text.matches("-?\\d+(?:\\.\\d+)?") ? new BigDecimal(text) : null;
    }

    private List<BigDecimal> numbers(String text) {
        String scrubbed = DATE.matcher(StringUtils.defaultString(text)).replaceAll(" ");
        scrubbed = YEAR.matcher(scrubbed).replaceAll(" ");
        scrubbed = CODE.matcher(scrubbed).replaceAll(" ");
        List<BigDecimal> values = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(scrubbed);
        while (matcher.find()) {
            values.add(new BigDecimal(matcher.group()));
        }
        return values;
    }

    private boolean roundedEquals(BigDecimal source, BigDecimal answer) {
        if (source.compareTo(answer) == 0) {
            return true;
        }
        for (int scale = 0; scale <= 4; scale++) {
            if (source.setScale(scale, RoundingMode.HALF_UP).compareTo(answer) == 0) {
                return true;
            }
        }
        return false;
    }

    private BankRequestContract requestContract(ExecuteContext context) {
        Object value = context.getParseInfo().getProperties().get(BankRequestContract.PROPERTY_KEY);
        if (value instanceof BankRequestContract requirements) {
            return requirements;
        }
        try {
            return value == null ? null : JsonUtil.toObject(JsonUtil.toString(value), BankRequestContract.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Validation invalid(String error) {
        return new Validation(false, List.of(error), null);
    }

    private String safeMessage(Exception exception) {
        return StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getSimpleName());
    }

    private String safeMessage(RuntimeException exception) {
        return StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getSimpleName());
    }

    private boolean reject(ExecuteContext context, String reason) {
        recordTrace(context, "SKIPPED", 0, List.of(reason), 0);
        return false;
    }

    private void recordTrace(ExecuteContext context, String status, int attempts, List<String> errors,
            int factCount) {
        if (context == null || context.getParseInfo() == null
                || context.getParseInfo().getProperties() == null) {
            return;
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("status", status);
        trace.put("attempts", attempts);
        trace.put("errors", List.copyOf(errors));
        trace.put("factCount", factCount);
        context.getParseInfo().getProperties().put(TRACE_PROPERTY, trace);
    }

    @FunctionalInterface
    interface AnswerGenerator {
        String generate(ChatApp chatApp, Prompt prompt);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class AnswerResponse {
        public String answer;
        public List<String> factIds;
    }

    private record ResultFact(String id, BankRequestContract.AnswerFactType type, String field,
            Object value, Map<String, Object> context) {}

    private record TrendPoint(String date, BigDecimal value, Map<String, Object> context) {}

    private record Validation(boolean valid, List<String> errors, AnswerResponse response) {}
}
