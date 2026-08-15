package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bank-plan adapter over two {@link FixedSystemPrefixLlmCache} instances: one per stage
 * (REQUIREMENTS / PLAN), each with its own system prefix and prefix version.
 *
 * <p>Because the two stages share {@link BankPlanPromptComposer#COMMON_FACT_PREFIX}, the common
 * fact tokens stay byte-identical at the head of both stage prompts, so llama.cpp can keep reusing
 * the shared prefix across stage alternation while each stage still has its own stable tail
 * (KV-prefix reuse per stage).
 *
 * <p>Completion memo keys bind stage + prefix version + dynamic user content, so a REQUIREMENTS
 * memo can never serve a PLAN call and vice versa. Memo hits are reported separately from real KV
 * cache hits and must not be counted as model-performance gains.
 *
 * <p>Thinking can be enabled via system property {@code s2.parser.bank.plan.thinking.enable=true}
 * (or the matching system parameter once loaded into JVM system properties). Thinking and
 * non-thinking use different prefix-version keys so KV caches do not mix.
 */
public class BankPlanLlmPrefixCache {

    public static final String THINKING_PROPERTY = "s2.parser.bank.plan.thinking.enable";
    private static final int DEFAULT_MEMO_CAPACITY = 256;
    private static final int DEFAULT_THINKING_MAX_TOKENS = 8192;

    /**
     * Safety cap for one REQUIREMENTS decode. Observed regular maximum is about 342 tokens; the
     * cap is a runaway-protection bound and stays above the verified maximum (and any repair hop).
     */
    public static final int REQUIREMENTS_MAX_OUTPUT_TOKENS = 768;
    /**
     * Safety cap for one PLAN decode, applied to both normal and repair calls. Observed regular
     * maximum is about 457 tokens and long repairs about 593; the cap is a runaway-protection
     * bound above both.
     */
    public static final int PLAN_MAX_OUTPUT_TOKENS = 1024;

    private static final String REQUIREMENTS_WARM_PROBE = "前缀预热：忽略本条业务内容。\n"
            + "<stage>REQUIREMENTS</stage>\n"
            + "只输出一个短 JSON 占位对象，不执行任何业务理解。";
    private static final String PLAN_WARM_PROBE = "前缀预热：忽略本条业务内容。\n"
            + "<stage>PLAN</stage>\n"
            + "只输出一个短 JSON 占位对象，不执行任何业务理解。";

    private final FixedSystemPrefixLlmCache requirementsDelegate;
    private final FixedSystemPrefixLlmCache planDelegate;

    public BankPlanLlmPrefixCache() {
        this(DEFAULT_MEMO_CAPACITY, false, thinkingEnabledFromProperty());
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm) {
        this(memoCapacity, autoWarm, thinkingEnabledFromProperty());
    }

    public BankPlanLlmPrefixCache(int memoCapacity, boolean autoWarm, boolean enableThinking) {
        this.requirementsDelegate = buildDelegate(Stage.REQUIREMENTS, enableThinking, memoCapacity,
                autoWarm);
        this.planDelegate = buildDelegate(Stage.PLAN, enableThinking, memoCapacity, autoWarm);
    }

    private static FixedSystemPrefixLlmCache buildDelegate(Stage stage, boolean enableThinking,
            int memoCapacity, boolean autoWarm) {
        String version = stageVersion(stage) + (enableThinking ? ":think" : ":nothink");
        String prefix = stage == Stage.REQUIREMENTS
                ? BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX
                : BankPlanPromptComposer.PLAN_SYSTEM_PREFIX;
        String probe = stage == Stage.REQUIREMENTS ? REQUIREMENTS_WARM_PROBE : PLAN_WARM_PROBE;
        int cap = stage == Stage.REQUIREMENTS ? REQUIREMENTS_MAX_OUTPUT_TOKENS
                : PLAN_MAX_OUTPUT_TOKENS;
        return new FixedSystemPrefixLlmCache(prefix, version, memoCapacity, autoWarm, probe,
                enableThinking, DEFAULT_THINKING_MAX_TOKENS, stage.name(), cap);
    }

    /** Stage-aware prefix version; both stages share the same registry facts but distinct tails. */
    static String stageVersion(Stage stage) {
        return stage == Stage.REQUIREMENTS ? BankPlanPromptComposer.REQUIREMENTS_PREFIX_VERSION
                : BankPlanPromptComposer.PLAN_PREFIX_VERSION;
    }

    public static boolean thinkingEnabledFromProperty() {
        String value = System.getProperty(THINKING_PROPERTY);
        if (StringUtils.isBlank(value)) {
            value = System.getenv("S2_PARSER_BANK_PLAN_THINKING_ENABLE");
        }
        return Boolean.parseBoolean(StringUtils.defaultIfBlank(value, "false"));
    }

    public boolean isThinkingEnabled() {
        return requirementsDelegate.stats().get("enableThinking") instanceof Boolean thinking
                && thinking;
    }

    public void warmPrefix(ChatLanguageModel model, ChatModelConfig config) {
        requirementsDelegate.warmPrefix(model, config);
        planDelegate.warmPrefix(model, config);
    }

    public void warmPrefix(ChatLanguageModel model) {
        warmPrefix(model, null);
    }

    public String generate(ChatLanguageModel model, Stage stage, String dynamicUserContent,
            boolean useMemo) {
        return delegate(stage).generate(model, dynamicUserContent, useMemo);
    }

    public String generate(ChatLanguageModel model, ChatModelConfig config, Stage stage,
            String dynamicUserContent, boolean useMemo) {
        return delegate(stage).generate(model, config, dynamicUserContent, useMemo);
    }

    private FixedSystemPrefixLlmCache delegate(Stage stage) {
        return stage == Stage.REQUIREMENTS ? requirementsDelegate : planDelegate;
    }

    /** Per-stage stats under {@code requirements}/{@code plan} plus merged legacy counters. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Object> requirements = requirementsDelegate.stats();
        Map<String, Object> plan = planDelegate.stats();
        stats.put("requirements", requirements);
        stats.put("plan", plan);
        putSum(stats, requirements, plan, "completionHits");
        putSum(stats, requirements, plan, "completionMisses");
        putSum(stats, requirements, plan, "modelCalls");
        putSum(stats, requirements, plan, "llamaCppCalls");
        putSum(stats, requirements, plan, "llamaCppCacheHits");
        putSum(stats, requirements, plan, "llamaCppCacheTokens");
        putSum(stats, requirements, plan, "llamaCppPromptTokens");
        putSum(stats, requirements, plan, "llamaCppCompletionTokens");
        putSum(stats, requirements, plan, "llamaCppPromptMs");
        putSum(stats, requirements, plan, "llamaCppDecodeMs");
        stats.put("stageIsolation", true);
        return stats;
    }

    private static void putSum(Map<String, Object> target, Map<String, Object> left,
            Map<String, Object> right, String key) {
        long sum = toLong(left.get(key)) + toLong(right.get(key));
        target.put(key, sum);
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    public String composeFullPrompt(Stage stage, String dynamicUserContent) {
        return delegate(stage).composeFullPrompt(dynamicUserContent);
    }

    String memoKey(Stage stage, String dynamicUserContent) {
        return delegate(stage).memoKey(dynamicUserContent);
    }

    /** The two generation stages. Each maps to its own system prefix and prefix version. */
    public enum Stage {
        REQUIREMENTS, PLAN;

        public static Stage of(String name) {
            return Objects.requireNonNull(Stage.valueOf(name.toUpperCase(java.util.Locale.ROOT)),
                    "unknown stage");
        }
    }
}
