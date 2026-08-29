package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One fixed-prefix cache for the single-pass bank planning response and its repair retries. */
public class BankPlanLlmPrefixCache {

    public static final String THINKING_PROPERTY = "s2.parser.bank.plan.thinking.enable";
    private static final int DEFAULT_MEMO_CAPACITY = 256;
    private static final int DEFAULT_THINKING_MAX_TOKENS = 8192;

    /** Bounds the combined requirements + plan response without truncating known plan shapes. */
    public static final int SINGLE_PASS_MAX_OUTPUT_TOKENS = 2048;

    private static final String SINGLE_PASS_WARM_PROBE = "前缀预热：忽略本条业务内容。\n"
            + "<stage>SINGLE_PASS</stage>\n"
            + "只输出一个最短 BankPlanningResponse JSON 占位对象，不执行任何业务理解。";

    private final FixedSystemPrefixLlmCache delegate;

    public BankPlanLlmPrefixCache() {
        this(DEFAULT_MEMO_CAPACITY, false, thinkingEnabledFromProperty());
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm) {
        this(memoCapacity, autoWarm, thinkingEnabledFromProperty());
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm, boolean enableThinking) {
        String version = BankPlanPromptComposer.SINGLE_PASS_PREFIX_VERSION
                + (enableThinking ? ":think" : ":nothink");
        this.delegate = new FixedSystemPrefixLlmCache(
                BankPlanPromptComposer.SINGLE_PASS_SYSTEM_PREFIX, version, memoCapacity, autoWarm,
                SINGLE_PASS_WARM_PROBE, enableThinking, DEFAULT_THINKING_MAX_TOKENS,
                Stage.SINGLE_PASS.name(), SINGLE_PASS_MAX_OUTPUT_TOKENS);
    }

    public static boolean thinkingEnabledFromProperty() {
        String value = System.getProperty(THINKING_PROPERTY);
        if (StringUtils.isBlank(value)) {
            value = System.getenv("S2_PARSER_BANK_PLAN_THINKING_ENABLE");
        }
        return Boolean.parseBoolean(StringUtils.defaultIfBlank(value, "false"));
    }

    public boolean isThinkingEnabled() {
        return delegate.stats().get("enableThinking") instanceof Boolean thinking && thinking;
    }

    public void warmPrefix(ChatLanguageModel model, ChatModelConfig config) {
        delegate.warmPrefix(model, config);
    }

    public void warmPrefix(ChatLanguageModel model) {
        warmPrefix(model, null);
    }

    public String generate(ChatLanguageModel model, Stage stage, String dynamicUserContent,
            boolean useMemo) {
        requireSinglePass(stage);
        return delegate.generate(model, dynamicUserContent, useMemo);
    }

    public String generate(ChatLanguageModel model, ChatModelConfig config, Stage stage,
            String dynamicUserContent, boolean useMemo) {
        requireSinglePass(stage);
        return delegate.generate(model, config, dynamicUserContent, useMemo);
    }

    /** Exposes one stage plus the existing top-level counters for report compatibility. */
    public Map<String, Object> stats() {
        Map<String, Object> singlePass = delegate.stats();
        Map<String, Object> stats = new LinkedHashMap<>(singlePass);
        stats.put("singlePass", singlePass);
        stats.put("stageIsolation", false);
        return stats;
    }

    public String composeFullPrompt(Stage stage, String dynamicUserContent) {
        requireSinglePass(stage);
        return delegate.composeFullPrompt(dynamicUserContent);
    }

    String memoKey(Stage stage, String dynamicUserContent) {
        requireSinglePass(stage);
        return delegate.memoKey(dynamicUserContent);
    }

    private static void requireSinglePass(Stage stage) {
        if (Objects.requireNonNull(stage, "stage") != Stage.SINGLE_PASS) {
            throw new IllegalArgumentException("only SINGLE_PASS bank planning is supported");
        }
    }

    public enum Stage {
        SINGLE_PASS
    }
}
