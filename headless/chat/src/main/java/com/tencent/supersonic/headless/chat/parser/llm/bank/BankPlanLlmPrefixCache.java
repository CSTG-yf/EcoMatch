package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.Map;

/**
 * Bank-plan adapter over {@link FixedSystemPrefixLlmCache} with the plan JSON system prefix.
 */
public class BankPlanLlmPrefixCache {

    private static final int DEFAULT_MEMO_CAPACITY = 256;
    private static final String PLAN_WARM_PROBE = "前缀预热：忽略本条业务内容，只输出 "
            + "{\"version\":\"1.0\",\"intent\":\"UNKNOWN\",\"metrics\":[],"
            + "\"dimensions\":[],\"organizations\":[],\"time\":{\"startDate\":\"1970-01-01\","
            + "\"endDate\":\"1970-01-01\",\"granularity\":\"DAY\",\"comparison\":\"NONE\"},"
            + "\"filters\":[],\"calculation\":{\"type\":\"DIRECT\"},\"orderBy\":[],\"limit\":null,"
            + "\"output\":{\"columns\":[],\"orderSensitive\":false}}";

    private final FixedSystemPrefixLlmCache delegate;

    public BankPlanLlmPrefixCache() {
        this(DEFAULT_MEMO_CAPACITY, false);
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm) {
        this.delegate = new FixedSystemPrefixLlmCache(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX,
                BankPlanPromptComposer.PREFIX_VERSION, memoCapacity, autoWarm, PLAN_WARM_PROBE);
    }

    public void warmPrefix(ChatLanguageModel model, ChatModelConfig config) {
        delegate.warmPrefix(model, config);
    }

    public void warmPrefix(ChatLanguageModel model) {
        delegate.warmPrefix(model);
    }

    public String generate(ChatLanguageModel model, String dynamicUserContent, boolean useMemo) {
        return delegate.generate(model, dynamicUserContent, useMemo);
    }

    public String generate(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent, boolean useMemo) {
        return delegate.generate(model, config, dynamicUserContent, useMemo);
    }

    public Map<String, Object> stats() {
        return delegate.stats();
    }

    static String composeFullPrompt(String dynamicUserContent) {
        return BankPlanPromptComposer.FIXED_SYSTEM_PREFIX + "\n\n" + dynamicUserContent;
    }

    static String memoKey(String dynamicUserContent) {
        return BankPlanPromptComposer.PREFIX_VERSION + ":"
                + Integer.toHexString(dynamicUserContent.hashCode());
    }
}
