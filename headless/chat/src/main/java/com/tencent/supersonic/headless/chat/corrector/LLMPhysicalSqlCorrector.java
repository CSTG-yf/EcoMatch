package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.jsqlparser.SqlValidHelper;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 物理SQL修正器 - 使用LLM优化物理SQL性能
 */
@Slf4j
public class LLMPhysicalSqlCorrector extends BaseSemanticCorrector {

    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");

    public static final String APP_KEY = "PHYSICAL_SQL_CORRECTOR";
    public static final String EXECUTION_APP_KEY = "EXECUTION_SQL_CORRECTOR";
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern TABLE =
            Pattern.compile("\\b(?:from|join)\\s+([`\"\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final String INSTRUCTION = ""
            + "#Role: You are a senior database performance optimization expert experienced in SQL tuning."
            + "\n\n#Task: You will be provided with a user question and the corresponding physical SQL query,"
            + " please analyze and optimize this SQL to improve query performance." + "\n\n#Rules:"
            + "\n1. DO NOT add or introduce any new fields, columns, or aliases that are not in the original SQL."
            + "\n2. Push WHERE conditions into JOIN ON clauses when possible to reduce intermediate result sets."
            + "\n3. Optimize JOIN order by placing smaller tables or tables with selective conditions first."
            + "\n4. For date range conditions, ensure they are applied as early as possible in the query execution."
            + "\n5. Remove or comment out database-specific index hints (like USE INDEX) that may cause syntax errors."
            + "\n6. ONLY modify the structure and order of existing elements, do not change field names or add new ones."
            + "\n7. Ensure the optimized SQL is syntactically correct and logically equivalent to the original."
            + "\n\n#Question: {{question}}" + "\n\n#OriginalSQL: {{sql}}";
    private static final String EXECUTION_INSTRUCTION = ""
            + "#Role: You repair a failed read-only SQL query.\n" + "#Rules:\n"
            + "1. Return only one corrected SELECT or WITH query.\n"
            + "2. Preserve every table, selected business field, filter and literal value.\n"
            + "3. Never broaden the data range, remove a condition or add a write operation.\n"
            + "4. Change only what is necessary to address the database error.\n"
            + "#Question: {{question}}\n#SQL: {{sql}}\n#DatabaseError: {{error}}";

    public LLMPhysicalSqlCorrector() {
        ChatAppManager.register(APP_KEY, ChatApp.builder().prompt(INSTRUCTION).name("物理SQL修正")
                .appModule(AppModule.CHAT).description("通过大模型对物理SQL做性能优化").enable(false).build());
        ChatAppManager.register(EXECUTION_APP_KEY,
                ChatApp.builder().prompt(EXECUTION_INSTRUCTION).name("执行错误 SQL 修复")
                        .appModule(AppModule.CHAT).description("根据数据库错误安全修复一次物理 SQL").enable(false)
                        .build());
    }

    @Data
    @ToString
    static class PhysicalSql {
        @Description("either positive or negative")
        private String opinion;

        @Description("optimized sql if negative")
        private String sql;
    }

    interface PhysicalSqlExtractor {
        PhysicalSql generatePhysicalSql(String text);
    }

    interface ExecutionRepairExtractor {
        String repair(String text);
    }

    public static String repairExecutionError(ChatApp chatApp, String question, String sql,
            String error) {
        if (chatApp == null || !chatApp.isEnable() || StringUtils.isAnyBlank(sql, error)) {
            return null;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("question", StringUtils.defaultString(question));
        variables.put("sql", sql);
        variables.put("error", error);
        Prompt prompt = PromptTemplate.from(chatApp.getPrompt()).apply(variables);
        ChatLanguageModel model = ModelProvider.getChatModel(chatApp.getChatModelConfig());
        String repaired =
                AiServices.create(ExecutionRepairExtractor.class, model).repair(prompt.text());
        return isSafeRepair(sql, repaired) ? repaired : null;
    }

    public static boolean isSafeRepair(String original, String repaired) {
        if (!SqlValidHelper.isValidSQL(repaired)
                || !repaired.trim().toLowerCase().matches("^(select|with)\\b[\\s\\S]*")) {
            return false;
        }
        if (!extract(STRING_LITERAL, repaired, 0)
                .containsAll(extract(STRING_LITERAL, original, 0))) {
            return false;
        }
        if (!extract(TABLE, repaired, 1).equals(extract(TABLE, original, 1))) {
            return false;
        }
        if (!new HashSet<>(SqlSelectHelper.getAllSelectFields(repaired))
                .containsAll(SqlSelectHelper.getAllSelectFields(original))) {
            return false;
        }
        Set<String> originalFilters = SqlSelectHelper
                .getFilterExpression(original).stream().map(filter -> filter.getFieldName() + "|"
                        + filter.getOperator() + "|" + String.valueOf(filter.getFieldValue()))
                .collect(Collectors.toSet());
        Set<String> repairedFilters = SqlSelectHelper
                .getFilterExpression(repaired).stream().map(filter -> filter.getFieldName() + "|"
                        + filter.getOperator() + "|" + String.valueOf(filter.getFieldValue()))
                .collect(Collectors.toSet());
        return repairedFilters.containsAll(originalFilters);
    }

    private static Set<String> extract(Pattern pattern, String value, int group) {
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(StringUtils.defaultString(value));
        while (matcher.find()) {
            result.add(matcher.group(group).toLowerCase());
        }
        return result;
    }

    @Override
    public void doCorrect(ChatQueryContext chatQueryContext, SemanticParseInfo semanticParseInfo) {
        ChatApp chatApp = chatQueryContext.getRequest().getChatAppConfig().get(APP_KEY);
        if (!chatQueryContext.getRequest().getText2SQLType().enableLLM() || Objects.isNull(chatApp)
                || !chatApp.isEnable()) {
            return;
        }

        ChatLanguageModel chatLanguageModel =
                ModelProvider.getChatModel(chatApp.getChatModelConfig());
        PhysicalSqlExtractor extractor =
                AiServices.create(PhysicalSqlExtractor.class, chatLanguageModel);
        Prompt prompt = generatePrompt(chatQueryContext.getRequest().getQueryText(),
                semanticParseInfo, chatApp.getPrompt());
        PhysicalSql physicalSql =
                extractor.generatePhysicalSql(prompt.toUserMessage().singleText());
        keyPipelineLog.info("LLMPhysicalSqlCorrector modelReq=[{}], modelResp=[{}]",
                SensitiveLogUtils.summarize(prompt.text()),
                SensitiveLogUtils.summarize(physicalSql));
        if ("NEGATIVE".equalsIgnoreCase(physicalSql.getOpinion())
                && StringUtils.isNotBlank(physicalSql.getSql())) {
            semanticParseInfo.getSqlInfo().setCorrectedQuerySQL(physicalSql.getSql());
        }
    }

    private Prompt generatePrompt(String queryText, SemanticParseInfo semanticParseInfo,
            String promptTemplate) {
        Map<String, Object> variable = new HashMap<>();
        variable.put("question", queryText);
        variable.put("sql", semanticParseInfo.getSqlInfo().getQuerySQL());

        return PromptTemplate.from(promptTemplate).apply(variable);
    }
}
