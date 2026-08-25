package com.tencent.supersonic.headless.chat.parser.llm;

import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFreeSqlPromptComposer;
import com.tencent.supersonic.headless.chat.parser.llm.bank.FixedSystemPrefixLlmCache;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.tencent.supersonic.headless.chat.parser.ParserConfig.PARSER_BANK_FREE_SQL_THINKING_ENABLE;
import static com.tencent.supersonic.headless.chat.parser.ParserConfig.PARSER_FORMAT_JSON_TYPE;

@Service
@Slf4j
public class OnePassSCSqlGenStrategy extends SqlGenStrategy {

    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");

    public static final String APP_KEY = "S2SQL_PARSER";

    @Autowired
    private ParserConfig parserConfig;

    /**
     * Bank free-SQL prefix caches keyed by stable-schema fingerprint. System message includes
     * frozen metric/dimension catalog so llama.cpp can KV-cache rules+schema; user is question-only.
     */
    private final ConcurrentHashMap<String, FixedSystemPrefixLlmCache> bankFreeSqlPrefixCaches =
            new ConcurrentHashMap<>();

    public static final String INSTRUCTION =
            "#Role: You are a data analyst experienced in SQL languages."
                    + "\n#Task: You will be provided with a natural language question asked by users,"
                    + "please convert it to a SQL query so that relevant data could be returned "
                    + "by executing the SQL query against underlying database." + "\n#Rules:"
                    + "\n1.SQL columns and values must be mentioned in the `Schema`, DO NOT hallucinate."
                    + "\n2.ALWAYS specify time range using `>`,`<`,`>=`,`<=` operator."
                    + "\n3.DO NOT include time range in the where clause if not explicitly expressed in the `Question`."
                    + "\n4.DO NOT calculate date range using functions."
                    + "\n5.ALWAYS use `with` statement if nested aggregation is needed."
                    + "\n6.ALWAYS enclose alias declared by `AS` command in underscores."
                    + "\n7.Alias created by `AS` command must be in the same language ast the `Question`."
                    + "\n#Exemplars: {{exemplar}}"
                    + "\n#Query: Question:{{question}},Schema:{{schema}},SideInfo:{{information}}";

    public OnePassSCSqlGenStrategy() {
        ChatAppManager.register(APP_KEY, ChatApp.builder().prompt(INSTRUCTION).name("语义SQL解析")
                .appModule(AppModule.CHAT).description("通过大模型做语义解析生成S2SQL").enable(true).build());
    }

    @Data
    static class SemanticSql {
        @Description("thought or remarks to tell users about the sql, make it short.")
        private String thought;

        @Description("sql to generate")
        private String sql;
    }

    interface SemanticSqlExtractor {
        SemanticSql generateSemanticSql(String text);
    }

    @Override
    public String getAppKey() {
        return APP_KEY;
    }

    @Override
    public LLMResp generate(LLMReq llmReq) {
        LLMResp llmResp = new LLMResp();
        llmResp.setQuery(llmReq.getQueryText());
        log.debug("OnePassSCSqlGenStrategy request=[{}]", SensitiveLogUtils.summarize(llmReq));

        String dataSemantics = promptHelper.buildSchemaStr(llmReq);
        String sideInformation = promptHelper.buildSideInformation(llmReq);
        llmResp.setSchema(dataSemantics);
        llmResp.setSideInfo(sideInformation);

        ChatApp chatApp = llmReq.getChatAppConfig().get(APP_KEY);
        ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();
        boolean bankDataset = BankFreeSqlPromptComposer.isBankSchema(llmReq.getSchema());
        boolean bankThinking = bankDataset && Boolean.parseBoolean(
                parserConfig.getParameterValue(PARSER_BANK_FREE_SQL_THINKING_ENABLE));
        // Deep thinking emits reasoning tokens before JSON; forced json_object breaks many servers.
        if (!bankThinking
                && !StringUtils.isBlank(parserConfig.getParameterValue(PARSER_FORMAT_JSON_TYPE))) {
            chatModelConfig.setJsonFormat(true);
            chatModelConfig
                    .setJsonFormatType(parserConfig.getParameterValue(PARSER_FORMAT_JSON_TYPE));
        } else if (bankThinking) {
            chatModelConfig.setJsonFormat(false);
            if (chatModelConfig.getTimeOut() == null || chatModelConfig.getTimeOut() < 300L) {
                chatModelConfig.setTimeOut(300L);
            }
        }

        // Bank free-SQL must never fall back to the generic blob that re-embeds schema/catalogs
        // into the user turn. Schema stays in the fixed system prefix; user = question + side info.
        if (bankDataset) {
            if (chatModelConfig == null || StringUtils.isBlank(chatModelConfig.getBaseUrl())) {
                keyPipelineLog.warn(
                        "OnePassSCSqlGenStrategy bank free-SQL without llama.cpp baseUrl; still using system-prefix + question-only user (no schema-in-user blob)");
            }
            return generateBankFreeSqlWithPrefix(llmReq, llmResp, chatModelConfig, dataSemantics,
                    sideInformation, bankThinking);
        }
        return generateGeneric(llmReq, llmResp, chatApp, chatModelConfig, dataSemantics,
                sideInformation);
    }

    /**
     * Bank free-SQL via llama.cpp system/user split + cache_prompt (real prefix KV).
     *
     * <p>System = rules + <b>frozen full schema catalog</b> (prefix-cached). User = question +
     * SideInfo only, so hits should leave only a short prompt_n.
     */
    private LLMResp generateBankFreeSqlWithPrefix(LLMReq llmReq, LLMResp llmResp,
            ChatModelConfig chatModelConfig, String dataSemantics, String sideInformation,
            boolean enableThinking) {
        String stableSchema = BankFreeSqlPromptComposer.buildStableSchemaBlock(llmReq.getSchema());
        String systemPrefix = BankFreeSqlPromptComposer.composeSystemPrefix(stableSchema);
        // Thinking flag is part of the cache identity so non-thinking warm-ups are not reused.
        String prefixVersion = BankFreeSqlPromptComposer.prefixVersion(stableSchema)
                + (enableThinking ? ":think" : ":nothink");
        final boolean thinking = enableThinking;
        FixedSystemPrefixLlmCache cache = bankFreeSqlPrefixCaches.computeIfAbsent(prefixVersion,
                key -> new FixedSystemPrefixLlmCache(systemPrefix, key, 256, false,
                        BankFreeSqlPromptComposer.freeSqlWarmProbe(), thinking,
                        thinking ? 8192 : 0));

        keyPipelineLog.info(
                "OnePassSCSqlGenStrategy bank free-SQL prefix path version={} schemaInPrefix=true thinking={} skipFewShot=true serial=true stableSchemaChars={}",
                prefixVersion, enableThinking, stableSchema.length());

        List<Text2SQLExemplar> emptyExemplars = Lists.newArrayList();
        ChatLanguageModel chatLanguageModel = getChatLanguageModel(chatModelConfig);

        String valuesHint = extractValuesHint(dataSemantics);
        String userContent = BankFreeSqlPromptComposer.buildQuestionOnlyUserContent(
                llmReq.getQueryText(), sideInformation, valuesHint);
        String raw =
                cache.generate(chatLanguageModel, chatModelConfig, userContent, false);
        String sql = BankFreeSqlPromptComposer.extractSql(raw);
        if (StringUtils.isBlank(sql)) {
            log.warn("OnePass bank free-SQL empty sql from model, rawChars={}",
                    raw == null ? 0 : raw.length());
            llmResp.setSqlOutput("");
            llmResp.setSqlRespMap(Map.of());
            return llmResp;
        }
        if (BankFreeSqlPromptComposer.looksInvalidBankS2Sql(sql)) {
            keyPipelineLog.warn(
                    "OnePassSCSqlGenStrategy bank S2SQL looks invalid (long-table/ZB-column style) sql=[{}]",
                    SensitiveLogUtils.summarize(sql));
        }
        keyPipelineLog.info(
                "OnePassSCSqlGenStrategy bankPrefix modelUser=[{}], modelResp=[{}], sql=[{}], invalidStyle={}, userChars={}",
                SensitiveLogUtils.summarize(userContent), SensitiveLogUtils.summarize(raw),
                SensitiveLogUtils.summarize(sql),
                BankFreeSqlPromptComposer.looksInvalidBankS2Sql(sql), userContent.length());

        Map<String, Double> vote = new HashMap<>();
        vote.put(sql, 1.0d);
        llmResp.setSqlOutput(sql);
        llmResp.setSqlRespMap(ResponseHelper.buildSqlRespMap(emptyExemplars, vote));
        keyPipelineLog.info("OnePassSCSqlGenStrategy bank free-SQL prefix stats={}", cache.stats());
        return llmResp;
    }

    /** Pull Values=[...] from full schema string if present (per-question linking). */
    private static String extractValuesHint(String fullSchemaStr) {
        if (fullSchemaStr == null) {
            return "";
        }
        int idx = fullSchemaStr.indexOf("Values=[");
        if (idx < 0) {
            return "";
        }
        return fullSchemaStr.substring(idx);
    }

    private LLMResp generateGeneric(LLMReq llmReq, LLMResp llmResp, ChatApp chatApp,
            ChatModelConfig chatModelConfig, String dataSemantics, String sideInformation) {
        List<List<Text2SQLExemplar>> exemplarsList = promptHelper.getFewShotExemplars(llmReq);
        ChatLanguageModel chatLanguageModel = getChatLanguageModel(chatModelConfig);
        SemanticSqlExtractor extractor =
                AiServices.create(SemanticSqlExtractor.class, chatLanguageModel);

        Map<Prompt, List<Text2SQLExemplar>> prompt2Exemplar = new HashMap<>();
        for (List<Text2SQLExemplar> exemplars : exemplarsList) {
            llmReq.setDynamicExemplars(exemplars);
            Prompt prompt = generatePrompt(llmReq, llmResp, chatApp, dataSemantics, sideInformation);
            prompt2Exemplar.put(prompt, exemplars);
        }

        Map<String, Prompt> output2Prompt = new ConcurrentHashMap<>();
        prompt2Exemplar.keySet().parallelStream().forEach(prompt -> {
            SemanticSql s2Sql = extractor.generateSemanticSql(prompt.toUserMessage().singleText());
            output2Prompt.put(s2Sql.getSql(), prompt);
            keyPipelineLog.info("OnePassSCSqlGenStrategy modelReq=[{}], modelResp=[{}]",
                    SensitiveLogUtils.summarize(prompt.text()), SensitiveLogUtils.summarize(s2Sql));
        });

        Pair<String, Map<String, Double>> sqlMapPair =
                ResponseHelper.selfConsistencyVote(Lists.newArrayList(output2Prompt.keySet()));
        llmResp.setSqlOutput(sqlMapPair.getLeft());
        List<Text2SQLExemplar> usedExemplars =
                prompt2Exemplar.get(output2Prompt.get(sqlMapPair.getLeft()));
        llmResp.setSqlRespMap(ResponseHelper.buildSqlRespMap(usedExemplars, sqlMapPair.getRight()));
        return llmResp;
    }

    private Prompt generatePrompt(LLMReq llmReq, LLMResp llmResp, ChatApp chatApp,
            String dataSemantics, String sideInformation) {
        StringBuilder exemplars = new StringBuilder();
        for (Text2SQLExemplar exemplar : llmReq.getDynamicExemplars()) {
            String exemplarStr = String.format("\nQuestion:%s,Schema:%s,SideInfo:%s,SQL:%s",
                    exemplar.getQuestion(), exemplar.getDbSchema(), exemplar.getSideInfo(),
                    exemplar.getSql());
            exemplars.append(exemplarStr);
        }

        Map<String, Object> variable = new HashMap<>();
        variable.put("exemplar", exemplars);
        variable.put("question", llmReq.getQueryText());
        variable.put("schema", dataSemantics);
        variable.put("information", sideInformation);

        // Non-bank only. Bank traffic is forced onto generateBankFreeSqlWithPrefix above so the
        // user turn never receives Metrics=/Dimensions= schema catalogs.
        String promptTemplate = chatApp != null && StringUtils.isNotBlank(chatApp.getPrompt())
                ? chatApp.getPrompt()
                : INSTRUCTION;
        return PromptTemplate.from(promptTemplate).apply(variable);
    }

    @Override
    public void afterPropertiesSet() {
        SqlGenStrategyFactory
                .addSqlGenerationForFactory(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY, this);
    }
}
