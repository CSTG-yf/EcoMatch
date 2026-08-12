package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * Bank-plan adapter over {@link FixedSystemPrefixLlmCache} with the plan JSON system prefix.
 *
 * <p>Thinking can be enabled via system property {@code s2.parser.bank.plan.thinking.enable=true}
 * (or the matching system parameter once loaded into JVM system properties). Thinking and
 * non-thinking use different prefix-version keys so KV caches do not mix.
 */
public class BankPlanLlmPrefixCache {

    public static final String THINKING_PROPERTY = "s2.parser.bank.plan.thinking.enable";
    private static final int DEFAULT_MEMO_CAPACITY = 256;
    private static final int DEFAULT_THINKING_MAX_TOKENS = 8192;
    private static final String PLAN_WARM_PROBE = "前缀预热：忽略本条业务内容。\n"
            + "<stage>REQUIREMENTS</stage>\n"
            + "只输出一个短 JSON 占位对象，不执行任何业务理解。";

    private final FixedSystemPrefixLlmCache delegate;
    private final boolean enableThinking;

    public BankPlanLlmPrefixCache() {
        this(DEFAULT_MEMO_CAPACITY, false, thinkingEnabledFromProperty());
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm) {
        this(memoCapacity, autoWarm, thinkingEnabledFromProperty());
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm, boolean enableThinking) {
        this.enableThinking = enableThinking;
        String version = BankPlanPromptComposer.PREFIX_VERSION
                + (enableThinking ? ":think" : ":nothink");
        this.delegate = new FixedSystemPrefixLlmCache(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX,
                version, memoCapacity, autoWarm, PLAN_WARM_PROBE, enableThinking,
                enableThinking ? DEFAULT_THINKING_MAX_TOKENS : 0);
    }

    public static boolean thinkingEnabledFromProperty() {
        String value = System.getProperty(THINKING_PROPERTY);
        if (StringUtils.isBlank(value)) {
            value = System.getenv("S2_PARSER_BANK_PLAN_THINKING_ENABLE");
        }
        return Boolean.parseBoolean(StringUtils.defaultIfBlank(value, "false"));
    }

    public boolean isThinkingEnabled() {
        return enableThinking;
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
