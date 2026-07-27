package com.tencent.supersonic.chat.server.processor.execute;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DataInterpretProcessor interprets query result to make it more readable to the users.
 */
public class DataInterpretProcessor implements ExecuteResultProcessor {
    public static final String tip = "AI 回答中...\r\n";
    static final int MAX_ACTIVE_INTERPRETATIONS = 1_000;
    static final int MAX_SUMMARY_CHARACTERS = 100_000;
    static final int MAX_QUESTION_CHARACTERS = 4_096;
    static final int MAX_RESULT_TEXT_CHARACTERS = 2_000_000;
    static final long INTERPRETATION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");

    private static final Map<Long, StringBuffer> resultCache = new ConcurrentHashMap<>();
    private static final Map<Long, Long> resultCacheTouchedAt = new ConcurrentHashMap<>();

    public static final String APP_KEY = "DATA_INTERPRETER";
    private static final String INSTRUCTION = ""
            + "#Role: You are a data expert who communicates with business users everyday."
            + "\n#Task: Your will be provided with a question asked by a user and the relevant "
            + "result data queried from the databases, please interpret the data and organize a brief answer."
            + "\n#Rules: " + "\n1.ALWAYS respond in the use the same language as the `#Question`."
            + "\n2.ALWAYS reference some key data in the `#Answer`."
            + "\n#Question:{{question}} #Data:{{data}} #Answer:";

    public DataInterpretProcessor() {
        ChatAppManager.register(APP_KEY, ChatApp.builder().prompt(INSTRUCTION).name("结果数据解读")
                .appModule(AppModule.CHAT).description("通过大模型对结果数据做提炼总结").enable(false).build());
    }

    public static String getTextSummary(Long queryId) {
        cleanupExpiredEntries(System.currentTimeMillis());
        StringBuffer buffer = queryId == null ? null : resultCache.get(queryId);
        return buffer == null ? "" : buffer.toString();
    }

    public static synchronized Map<Long, StringBuffer> getResultCache() {
        cleanupExpiredEntries(System.currentTimeMillis());
        Map<Long, StringBuffer> snapshot = new LinkedHashMap<>();
        resultCache.forEach(
                (queryId, buffer) -> snapshot.put(queryId, new StringBuffer(buffer.toString())));
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    @Override
    public boolean accept(ExecuteContext executeContext) {
        Agent agent = executeContext.getAgent();
        ChatApp chatApp = agent.getChatAppConfig().get(APP_KEY);
        return Objects.nonNull(chatApp) && chatApp.isEnable()
                && StringUtils.isNotBlank(executeContext.getResponse().getTextResult()) // 如果都没结果，则无法处理
                && StringUtils.isBlank(executeContext.getResponse().getTextSummary()); // 如果已经有汇总的结果了，无法再次处理
    }

    @Override
    public void process(ExecuteContext executeContext) {
        QueryResult queryResult = executeContext.getResponse();
        Agent agent = executeContext.getAgent();
        ChatApp chatApp = agent.getChatAppConfig().get(APP_KEY);

        Map<String, Object> variable = new HashMap<>();
        String question = resolveQuestion(executeContext);
        String resultText = queryResult.getTextResult();
        if (!isInterpretationInputWithinLimits(question, resultText)) {
            keyPipelineLog.warn(
                    "DataInterpretProcessor input exceeds limits: questionChars={}, resultChars={}",
                    question.length(), resultText == null ? 0 : resultText.length());
            return;
        }
        variable.put("question", question);
        variable.put("data", resultText);

        Prompt prompt = PromptTemplate.from(chatApp.getPrompt()).apply(variable);
        if (executeContext.getRequest().isStreamingResult()) {
            StreamingChatLanguageModel chatLanguageModel =
                    ModelProvider.getChatStreamingModel(chatApp.getChatModelConfig());
            final Long queryId = executeContext.getRequest().getQueryId();
            initializeStreamingResult(queryId);
            try {
                chatLanguageModel.generate(prompt.toUserMessage(),
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                appendStreamingToken(queryId, token);
                            }

                            @Override
                            public void onComplete(Response<AiMessage> response) {
                                String summary = completeStreamingResult(queryId);
                                if (summary == null) {
                                    return;
                                }
                                ChatQueryRepository chatQueryRepository =
                                        ContextUtils.getBean(ChatQueryRepository.class);
                                ChatQueryDO chatQueryDO =
                                        chatQueryRepository.getChatQueryDO(queryId);
                                if (chatQueryDO == null) {
                                    return;
                                }
                                JSONObject queryResult =
                                        JSON.parseObject(chatQueryDO.getQueryResult());
                                if (queryResult == null) {
                                    return;
                                }
                                queryResult.put("textSummary", summary);
                                chatQueryDO.setQueryResult(queryResult.toJSONString());
                                chatQueryRepository.updateChatQuery(chatQueryDO);
                            }

                            @Override
                            public void onError(Throwable error) {
                                keyPipelineLog.warn(
                                        "DataInterpretProcessor streaming failed: type={}, error=[{}]",
                                        error.getClass().getSimpleName(),
                                        SensitiveLogUtils.summarize(error));
                                removeStreamingResult(queryId);
                            }
                        });
            } catch (RuntimeException | Error exception) {
                removeStreamingResult(queryId);
                throw exception;
            }
        } else {
            ChatLanguageModel chatLanguageModel =
                    ModelProvider.getChatModel(chatApp.getChatModelConfig());
            Response<AiMessage> response = chatLanguageModel.generate(prompt.toUserMessage());
            String anwser = response.content().text();
            keyPipelineLog.info("DataInterpretProcessor modelReq=[{}], modelResp=[{}]",
                    SensitiveLogUtils.summarize(prompt.text()),
                    SensitiveLogUtils.summarize(anwser));
            if (StringUtils.isNotBlank(anwser) && anwser.length() <= MAX_SUMMARY_CHARACTERS) {
                queryResult.setTextSummary(anwser);
            } else if (anwser != null && anwser.length() > MAX_SUMMARY_CHARACTERS) {
                keyPipelineLog.warn("DataInterpretProcessor response exceeds maximum length: {}",
                        anwser.length());
            }
        }


    }

    static String resolveQuestion(ExecuteContext executeContext) {
        String question = executeContext.getRequest() == null ? ""
                : org.apache.commons.lang3.StringUtils
                        .defaultString(executeContext.getRequest().getQueryText());
        if (executeContext.getParseInfo() == null
                || executeContext.getParseInfo().getProperties() == null) {
            return question;
        }
        Object contextValue = executeContext.getParseInfo().getProperties().get("CONTEXT");
        if (!(contextValue instanceof Map<?, ?> context)) {
            return question;
        }
        Object rewrittenQuestion = context.get("queryText");
        return rewrittenQuestion == null
                || org.apache.commons.lang3.StringUtils.isBlank(rewrittenQuestion.toString())
                        ? question
                        : rewrittenQuestion.toString();
    }

    static boolean isInterpretationInputWithinLimits(String question, String resultText) {
        return question != null && resultText != null
                && question.length() <= MAX_QUESTION_CHARACTERS
                && resultText.length() <= MAX_RESULT_TEXT_CHARACTERS;
    }

    static synchronized void initializeStreamingResult(Long queryId) {
        if (queryId == null) {
            throw new IllegalStateException("Streaming interpretation query id is required");
        }
        cleanupExpiredEntries(System.currentTimeMillis());
        if (resultCache.containsKey(queryId)) {
            throw new IllegalStateException("Streaming interpretation is already active");
        }
        if (resultCache.size() >= MAX_ACTIVE_INTERPRETATIONS) {
            throw new IllegalStateException("Streaming interpretation capacity reached");
        }
        resultCache.put(queryId, new StringBuffer(tip));
        resultCacheTouchedAt.put(queryId, System.currentTimeMillis());
    }

    static void appendStreamingToken(Long queryId, String token) {
        if (queryId == null || token == null || token.isEmpty()) {
            return;
        }
        StringBuffer buffer = resultCache.get(queryId);
        if (buffer == null) {
            return;
        }
        boolean oversized = false;
        synchronized (buffer) {
            int summaryLength = buffer.length() - tip.length();
            if (summaryLength < 0 || token.length() > MAX_SUMMARY_CHARACTERS - summaryLength) {
                oversized = true;
            } else {
                buffer.append(token);
            }
        }
        if (oversized) {
            removeStreamingResult(queryId, buffer);
        } else {
            touchStreamingResult(queryId, buffer);
        }
    }

    static String completeStreamingResult(Long queryId) {
        StringBuffer buffer = removeStreamingResult(queryId);
        if (buffer == null || buffer.length() < tip.length()) {
            return null;
        }
        return buffer.substring(tip.length());
    }

    static synchronized void cleanupExpiredEntries(long nowMillis) {
        resultCacheTouchedAt.forEach((queryId, touchedAt) -> {
            if (touchedAt == null || nowMillis - touchedAt >= INTERPRETATION_TTL_MILLIS) {
                resultCache.remove(queryId);
                resultCacheTouchedAt.remove(queryId);
            }
        });
    }

    static synchronized StringBuffer removeStreamingResult(Long queryId) {
        if (queryId == null) {
            return null;
        }
        resultCacheTouchedAt.remove(queryId);
        return resultCache.remove(queryId);
    }

    private static synchronized void removeStreamingResult(Long queryId,
            StringBuffer expectedBuffer) {
        if (queryId != null && resultCache.get(queryId) == expectedBuffer) {
            resultCacheTouchedAt.remove(queryId);
            resultCache.remove(queryId);
        }
    }

    private static synchronized void touchStreamingResult(Long queryId,
            StringBuffer expectedBuffer) {
        if (queryId != null && resultCache.get(queryId) == expectedBuffer) {
            resultCacheTouchedAt.put(queryId, System.currentTimeMillis());
        }
    }

    static synchronized void resetStreamingResults() {
        resultCache.clear();
        resultCacheTouchedAt.clear();
    }
}
