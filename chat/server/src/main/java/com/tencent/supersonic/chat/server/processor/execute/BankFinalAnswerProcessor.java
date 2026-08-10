package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates the score-bearing direct answer for a successfully projected bank query. */
public class BankFinalAnswerProcessor implements ExecuteResultProcessor {

    public static final String APP_KEY = "BANK_FINAL_ANSWER";
    public static final String TRACE_PROPERTY = "bank.nl2sql.finalAnswerTrace";
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_ANSWER_CHARACTERS = 4_096;
    static final int MAX_RESULT_CHARACTERS = 200_000;

    public static final String INSTRUCTION = """
            # Role
            你是银行问数 Agent 的最终回答器。根据用户问题、已经通过校验的 BankQueryPlan 和数据库执行结果，直接回答用户。

            # Hard rules
            1. 只回答问题明确询问的事实，不复述问题，不解释查询过程。
            2. 禁止输出记录数、数据范围、首末记录、额外最大最小值、免责声明、SQL、字段分析或推理过程，除非问题明确询问对应事实。
            3. 每个数字都必须来自 Result；日期和题面阈值可以来自 Question。不得编造或引入额外数字。
            4. 百分比和业务数值通常四舍五入到小数点后两位，整数保持整数；用正负号判断增长/上升或下降。
            5. percent_change 表示变化率，absolute_change 表示变化额，ratio_percent 表示占比，rank_position 表示名次；current_value/baseline_value 只是支撑值，问题未询问时不要输出。
            6. 问“增幅、变化百分比”只回答 percent_change；问“增加/减少/变动了多少”优先回答 absolute_change；问“占比/比重”回答 ratio_percent。
            7. 同时询问环比和同比时必须分别回答两者；排名、趋势、多机构或多指标问题按 Result 的行身份逐项回答。
            8. 使用与 Question 相同的语言，输出一至三句纯文本，不要 Markdown、JSON、标签或前后缀。

            # Question
            {{question}}
            # BankQueryPlan
            {{plan}}
            # Result
            {{data}}
            # Previous answer
            {{previous_answer}}
            # validation_feedback
            {{validation_feedback}}
            # Direct answer
            """;

    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");
    private static final Pattern DATE = Pattern.compile("\\d{4}[-年/]\\d{1,2}(?:[-月/]\\d{1,2}日?)?");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:20[2-3]\\d)年?(?!\\d)");
    private static final Pattern CODE = Pattern.compile("(?i)(?:ORG|ZB)\\d{3}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final List<String> GENERIC_INSIGHT_MARKERS = List.of("问题范围：", "查询返回",
            "范围为", "首条记录", "末条记录", "结论仅适用于", "结果少于", "指标口径：");
    private static final List<String> TECHNICAL_FIELD_MARKERS = List.of("current_value",
            "baseline_value", "absolute_change", "percent_change", "metric_value",
            "ratio_percent", "rank_position", "observation_count");

    private final AnswerGenerator answerGenerator;

    public BankFinalAnswerProcessor() {
        this((chatApp, prompt) -> {
            ChatLanguageModel model =
                    ModelProvider.getChatModel(ModelConfigHelper.getChatModelConfig(chatApp));
            return model.generate(prompt.toUserMessage()).content().text();
        });
        ChatAppManager.register(APP_KEY,
                ChatApp.builder().prompt(INSTRUCTION).name("银行问数直接回答")
                        .description("基于已验证计划和查询结果生成简洁、可校验的最终答案")
                        .appModule(AppModule.CHAT).enable(false).build());
    }

    BankFinalAnswerProcessor(AnswerGenerator answerGenerator) {
        this.answerGenerator = Objects.requireNonNull(answerGenerator);
    }

    @Override
    public boolean accept(ExecuteContext context) {
        if (context == null || context.getResponse() == null || context.getAgent() == null
                || context.getParseInfo() == null || context.getParseInfo().getProperties() == null
                || context.getResponse().getQueryState() == null
                || !com.tencent.supersonic.headless.api.pojo.response.QueryState.SUCCESS
                        .equals(context.getResponse().getQueryState())
                || StringUtils.isNotBlank(context.getResponse().getTextSummary())
                || !context.getParseInfo().getProperties()
                        .containsKey(BankPlanToolResult.PLAN_PROPERTY_KEY)) {
            return false;
        }
        ChatApp chatApp = context.getAgent().getChatAppConfig() == null ? null
                : context.getAgent().getChatAppConfig().get(APP_KEY);
        return chatApp != null && chatApp.isEnable() && StringUtils.isNotBlank(chatApp.getPrompt())
                && context.getResponse().getQueryResults() != null
                && context.getResponse().getQueryColumns() != null;
    }

    @Override
    public void process(ExecuteContext context) {
        QueryResult result = context.getResponse();
        String question = DataInterpretProcessor.resolveQuestion(context);
        String data = resultPayload(result);
        if (StringUtils.isBlank(question) || data.length() > MAX_RESULT_CHARACTERS) {
            recordTrace(context, "FAILED", 0,
                    List.of("问题为空或结果超过最终回答输入上限"));
            return;
        }
        ChatApp chatApp = context.getAgent().getChatAppConfig().get(APP_KEY);
        String previousAnswer = "";
        List<String> feedback = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Prompt prompt = prompt(chatApp, context, question, data, previousAnswer, feedback);
            final String candidate;
            try {
                candidate = StringUtils.trimToEmpty(answerGenerator.generate(chatApp, prompt));
            } catch (RuntimeException exception) {
                keyPipelineLog.warn("Bank final answer generation failed: type={}",
                        exception.getClass().getSimpleName());
                recordTrace(context, "FAILED", attempt, List.of("MODEL_UNAVAILABLE"));
                return;
            }
            Validation validation = validate(candidate, question, result);
            if (validation.valid()) {
                result.setTextSummary(candidate);
                recordTrace(context, "SUCCEEDED", attempt, List.of());
                keyPipelineLog.info("Bank final answer accepted: attempts={}, response=[{}]",
                        attempt, SensitiveLogUtils.summarize(candidate));
                return;
            }
            previousAnswer = candidate;
            feedback = validation.errors();
        }
        recordTrace(context, "FAILED", MAX_ATTEMPTS, feedback);
    }

    private Prompt prompt(ChatApp chatApp, ExecuteContext context, String question, String data,
            String previousAnswer, List<String> feedback) {
        Object plan = context.getParseInfo().getProperties()
                .get(BankPlanToolResult.PLAN_PROPERTY_KEY);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", question);
        variables.put("plan", plan == null ? "{}" : JsonUtil.toString(plan));
        variables.put("data", data);
        variables.put("previous_answer", previousAnswer);
        variables.put("validation_feedback", feedback.isEmpty() ? "无"
                : "上一次回答未通过：" + String.join("；", feedback) + "。请只修正这些问题。"
                        + "不要输出查询记录数、范围或首末记录。");
        return PromptTemplate.from(chatApp.getPrompt()).apply(variables);
    }

    private String resultPayload(QueryResult result) {
        List<String> columns = result.getQueryColumns().stream().filter(Objects::nonNull)
                .map(this::columnName).toList();
        return JsonUtil.toString(Map.of("columns", columns, "rows", result.getQueryResults()));
    }

    private String columnName(QueryColumn column) {
        return StringUtils.defaultIfBlank(column.getBizName(),
                StringUtils.defaultIfBlank(column.getNameEn(), column.getName()));
    }

    private Validation validate(String answer, String question, QueryResult result) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(answer)) {
            return new Validation(false, List.of("回答为空"));
        }
        if (answer.length() > MAX_ANSWER_CHARACTERS) {
            errors.add("回答过长");
        }
        if (GENERIC_INSIGHT_MARKERS.stream().anyMatch(answer::contains)) {
            errors.add("回答仍是通用数据摘要");
        }
        if (TECHNICAL_FIELD_MARKERS.stream().anyMatch(answer::contains)) {
            errors.add("回答暴露了技术字段名");
        }
        List<BigDecimal> grounded = groundedNumbers(question, result);
        List<BigDecimal> ungrounded = numbers(answer).stream()
                .filter(value -> grounded.stream().noneMatch(source -> roundedEquals(source, value)))
                .toList();
        if (!ungrounded.isEmpty()) {
            errors.add("存在无法由问题或结果证明的数字：" + ungrounded);
        }
        if (question.contains("环比") && !answer.contains("环比")) {
            errors.add("缺少环比结论");
        }
        if (question.contains("同比") && !answer.contains("同比")) {
            errors.add("缺少同比结论");
        }
        if ((question.contains("增幅") || question.contains("百分之")) && !answer.contains("%")
                && !answer.contains("％")) {
            errors.add("缺少百分比结果");
        }
        if ((question.contains("最高") || question.contains("最大"))
                && !answer.matches("(?s).*(最高|最大).*")) {
            errors.add("缺少最高值结论");
        }
        if ((question.contains("最低") || question.contains("最小"))
                && !answer.matches("(?s).*(最低|最小).*")) {
            errors.add("缺少最低值结论");
        }
        return new Validation(errors.isEmpty(), List.copyOf(errors));
    }

    private List<BigDecimal> groundedNumbers(String question, QueryResult result) {
        List<BigDecimal> values = new ArrayList<>(numbers(question));
        for (Map<String, Object> row : result.getQueryResults()) {
            if (row == null) {
                continue;
            }
            for (Object value : row.values()) {
                if (value instanceof Number number) {
                    values.add(new BigDecimal(number.toString()));
                }
            }
        }
        return values;
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

    private void recordTrace(ExecuteContext context, String status, int attempts,
            List<String> errors) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("status", status);
        trace.put("attempts", attempts);
        trace.put("errors", List.copyOf(errors));
        context.getParseInfo().getProperties().put(TRACE_PROPERTY, trace);
    }

    @FunctionalInterface
    interface AnswerGenerator {
        String generate(ChatApp chatApp, Prompt prompt);
    }

    private record Validation(boolean valid, List<String> errors) {}
}
