package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reuses a byte-stable system prefix for llama.cpp ({@code cache_prompt=true}) and optional
 * process-local completion memo.
 *
 * <p>Request shape: {@code system=fixedPrefix} + {@code user=dynamic}. After warm-up, the server
 * reports {@code timings.cache_n &gt; 0} when the system tokens are reused from KV.
 */
public class FixedSystemPrefixLlmCache {

    private static final Logger LOG = LoggerFactory.getLogger(FixedSystemPrefixLlmCache.class);
    private static final Logger KEY_PIPELINE = LoggerFactory.getLogger("keyPipeline");
    private static final int DEFAULT_MEMO_CAPACITY = 256;

    private final String systemPrefix;
    private final String prefixVersion;
    private final String warmUserProbe;
    private final boolean autoWarm;
    private final AtomicBoolean prefixWarmed = new AtomicBoolean(false);
    private final AtomicLong prefixWarmAttempts = new AtomicLong();
    private final AtomicLong completionHits = new AtomicLong();
    private final AtomicLong completionMisses = new AtomicLong();
    private final AtomicLong modelCalls = new AtomicLong();
    private final AtomicLong llamaCppCalls = new AtomicLong();
    private final AtomicLong llamaCppCacheHits = new AtomicLong();
    private final AtomicLong llamaCppCacheTokens = new AtomicLong();
    private final AtomicLong llamaCppPromptTokens = new AtomicLong();
    private final AtomicLong llamaCppCompletionTokens = new AtomicLong();
    private final AtomicLong llamaCppPromptMs = new AtomicLong();
    private final AtomicLong llamaCppDecodeMs = new AtomicLong();
    private final boolean enableThinking;
    private final int thinkingMaxTokens;
    private final String stageLabel;
    private final int safetyMaxTokens;
    private final Map<String, String> completionMemo;
    private final LlamaCppPrefixChatClient llamaCppClient = new LlamaCppPrefixChatClient();

    public FixedSystemPrefixLlmCache(String systemPrefix, String prefixVersion) {
        this(systemPrefix, prefixVersion, DEFAULT_MEMO_CAPACITY, false, defaultWarmProbe(), false,
                0);
    }

    public FixedSystemPrefixLlmCache(String systemPrefix, String prefixVersion, int memoCapacity,
            boolean autoWarm) {
        this(systemPrefix, prefixVersion, memoCapacity, autoWarm, defaultWarmProbe(), false, 0);
    }

    public FixedSystemPrefixLlmCache(String systemPrefix, String prefixVersion, int memoCapacity,
            boolean autoWarm, String warmUserProbe) {
        this(systemPrefix, prefixVersion, memoCapacity, autoWarm, warmUserProbe, false, 0);
    }

    public FixedSystemPrefixLlmCache(String systemPrefix, String prefixVersion, int memoCapacity,
            boolean autoWarm, String warmUserProbe, boolean enableThinking, int thinkingMaxTokens) {
        this(systemPrefix, prefixVersion, memoCapacity, autoWarm, warmUserProbe, enableThinking,
                thinkingMaxTokens, "default", 0);
    }

    public FixedSystemPrefixLlmCache(String systemPrefix, String prefixVersion, int memoCapacity,
            boolean autoWarm, String warmUserProbe, boolean enableThinking, int thinkingMaxTokens,
            String stageLabel, int safetyMaxTokens) {
        this.systemPrefix = Objects.requireNonNull(systemPrefix, "systemPrefix");
        if (systemPrefix.isBlank()) {
            throw new IllegalArgumentException("systemPrefix must not be blank");
        }
        this.prefixVersion = Objects.requireNonNull(prefixVersion, "prefixVersion");
        this.autoWarm = autoWarm;
        this.warmUserProbe = StringUtils.defaultIfBlank(warmUserProbe, defaultWarmProbe());
        this.enableThinking = enableThinking;
        this.thinkingMaxTokens = thinkingMaxTokens;
        this.stageLabel = StringUtils.defaultIfBlank(stageLabel, "default");
        this.safetyMaxTokens = Math.max(0, safetyMaxTokens);
        int capacity = Math.max(16, memoCapacity);
        this.completionMemo =
                java.util.Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > capacity;
                    }
                });
    }

    private static String defaultWarmProbe() {
        return "前缀预热：忽略本条业务内容，只输出短占位响应。";
    }

    public String systemPrefix() {
        return systemPrefix;
    }

    public String prefixVersion() {
        return prefixVersion;
    }

    public void warmPrefix(ChatLanguageModel model, ChatModelConfig config) {
        boolean llamaCpp = config != null && StringUtils.isNotBlank(config.getBaseUrl());
        if ((!autoWarm && !llamaCpp) || !prefixWarmed.compareAndSet(false, true)) {
            return;
        }
        prefixWarmAttempts.incrementAndGet();
        try {
            callModel(model, config, warmUserProbe,
                    LlamaCppPrefixChatClient.ChatOptions.warmup(enableThinking));
            KEY_PIPELINE.info(
                    "FixedSystemPrefixLlmCache warmed fixed system prefix version={} via={}",
                    prefixVersion, llamaCpp ? "llama.cpp" : "langchain4j");
        } catch (RuntimeException ex) {
            prefixWarmed.set(false);
            LOG.warn("Fixed system prefix warm-up failed version={}: type={}, error=[{}]",
                    prefixVersion, ex.getClass().getSimpleName(),
                    ex.getMessage() == null ? ""
                            : ex.getMessage().substring(0, Math.min(160, ex.getMessage().length())));
        }
    }

    public void warmPrefix(ChatLanguageModel model) {
        warmPrefix(model, null);
    }

    public String generate(ChatLanguageModel model, String dynamicUserContent, boolean useMemo) {
        return generate(model, null, dynamicUserContent, useMemo);
    }

    public String generate(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent, boolean useMemo) {
        if (dynamicUserContent == null || dynamicUserContent.isBlank()) {
            throw new IllegalArgumentException("dynamic user content is required");
        }
        if (model == null && (config == null || StringUtils.isBlank(config.getBaseUrl()))) {
            throw new IllegalArgumentException("chat model or llama.cpp baseUrl is required");
        }
        warmPrefix(model, config);

        String memoKey = memoKey(dynamicUserContent);
        if (useMemo) {
            String cached = completionMemo.get(memoKey);
            if (cached != null) {
                completionHits.incrementAndGet();
                KEY_PIPELINE.info(
                        "FixedSystemPrefixLlmCache completion HIT prefixVersion={} key={}",
                        prefixVersion, shortKey(memoKey));
                return cached;
            }
            completionMisses.incrementAndGet();
        }

        modelCalls.incrementAndGet();
        String text = callModel(model, config, dynamicUserContent);
        if (useMemo && text != null && !text.isBlank()) {
            completionMemo.put(memoKey, text);
        }
        KEY_PIPELINE.info(
                "FixedSystemPrefixLlmCache completion MISS prefixVersion={} modelCalls={} memoSize={} llamaCppCacheHits={}",
                prefixVersion, modelCalls.get(), completionMemo.size(), llamaCppCacheHits.get());
        return text;
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stage", stageLabel);
        stats.put("prefixVersion", prefixVersion);
        stats.put("prefixWarmed", prefixWarmed.get());
        stats.put("autoWarm", autoWarm);
        stats.put("prefixWarmAttempts", prefixWarmAttempts.get());
        stats.put("completionHits", completionHits.get());
        stats.put("completionMisses", completionMisses.get());
        stats.put("modelCalls", modelCalls.get());
        stats.put("llamaCppCalls", llamaCppCalls.get());
        stats.put("llamaCppCacheHits", llamaCppCacheHits.get());
        stats.put("llamaCppCacheTokens", llamaCppCacheTokens.get());
        stats.put("llamaCppPromptTokens", llamaCppPromptTokens.get());
        stats.put("llamaCppCompletionTokens", llamaCppCompletionTokens.get());
        stats.put("llamaCppPromptMs", llamaCppPromptMs.get());
        stats.put("llamaCppDecodeMs", llamaCppDecodeMs.get());
        stats.put("safetyMaxTokens", safetyMaxTokens);
        stats.put("enableThinking", enableThinking);
        stats.put("thinkingMaxTokens", thinkingMaxTokens);
        stats.put("memoSize", completionMemo.size());
        return stats;
    }

    public String composeFullPrompt(String dynamicUserContent) {
        return systemPrefix + "\n\n" + dynamicUserContent;
    }

    String memoKey(String dynamicUserContent) {
        return prefixVersion + ":" + sha256(dynamicUserContent);
    }

    /**
     * Resolves the llama.cpp chat options for one call: an explicit request (warm-up or thinking)
     * wins; otherwise thinking wins when enabled; otherwise a safety cap bounds the decode, but
     * only for local llama.cpp endpoints. Remote OpenAI-compatible endpoints (e.g. the remote
     * DeepSeek reasoning endpoint) must not receive the implicit local safety cap, which would be
     * consumed by {@code reasoning_content} and truncate the returned JSON.
     */
    LlamaCppPrefixChatClient.ChatOptions resolveOptions(String baseUrl,
            LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        if (requestedOptions != null) {
            return requestedOptions;
        }
        if (enableThinking) {
            return LlamaCppPrefixChatClient.ChatOptions.thinking(thinkingMaxTokens);
        }
        if (safetyMaxTokens > 0 && isLocalLlamaCppEndpoint(baseUrl)) {
            return LlamaCppPrefixChatClient.ChatOptions.safetyCap(safetyMaxTokens);
        }
        return LlamaCppPrefixChatClient.ChatOptions.defaults();
    }

    /** Legacy single-arg overload; resolves against the configured safety cap for local endpoints. */
    LlamaCppPrefixChatClient.ChatOptions resolveOptions(
            LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        return resolveOptions(null, requestedOptions);
    }

    /**
     * The implicit safety cap is a llama.cpp-local guard: it applies to loopback and RFC1918
     * private endpoints only. A remote OpenAI-compatible baseUrl (https/https on a public host)
     * gets no implicit cap; explicit requested options always win and are decided elsewhere.
     */
    private static boolean isLocalLlamaCppEndpoint(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return true;
        }
        String host = hostOf(baseUrl);
        if (StringUtils.isBlank(host)) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(normalized) || "::1".equals(normalized)) {
            return true;
        }
        if (!IPV4_HOST.matcher(normalized).matches()) {
            return false;
        }
        String[] octets = normalized.split("\\.");
        int first = Integer.parseInt(octets[0]);
        int second = Integer.parseInt(octets[1]);
        if (first == 127) {
            return true;
        }
        if (first == 10) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        return first == 192 && second == 168;
    }

    private static final java.util.regex.Pattern IPV4_HOST =
            java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private static String hostOf(String baseUrl) {
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String callModel(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent) {
        return callModel(model, config, dynamicUserContent, null);
    }

    private String callModel(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent, LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        if (config != null && StringUtils.isNotBlank(config.getBaseUrl())) {
            try {
                LlamaCppPrefixChatClient.ChatResult result =
                        llamaCppClient.chat(config, systemPrefix, dynamicUserContent,
                                bindStageSchema(config, resolveOptions(config.getBaseUrl(),
                                        requestedOptions)));
                llamaCppCalls.incrementAndGet();
                recordLlamaCppTimings(result);
                return result.content();
            } catch (RuntimeException ex) {
                if (!shouldFallbackToLangchain(ex)) {
                    throw ex;
                }
                LOG.warn(
                        "llama.cpp prefix chat failed, falling back to langchain4j: version={} stage={} type={}, error=[{}]",
                        prefixVersion, stageLabel, ex.getClass().getSimpleName(),
                        ex.getMessage() == null ? ""
                                : ex.getMessage().substring(0,
                                        Math.min(160, ex.getMessage().length())));
                if (model == null) {
                    throw ex;
                }
            }
        }
        if (model == null) {
            throw new IllegalStateException("no chat model available after llama.cpp failure");
        }
        return model.generate(composeFullPrompt(dynamicUserContent));
    }

    static boolean shouldFallbackToLangchain(RuntimeException exception) {
        return !(exception instanceof LlamaCppPrefixChatClient.JsonSchemaCapabilityException);
    }

    LlamaCppPrefixChatClient.ChatOptions bindStageSchema(ChatModelConfig config,
            LlamaCppPrefixChatClient.ChatOptions options) {
        if (options.enableThinking() || options.omitResponseFormat()
                || !Boolean.TRUE.equals(config.getJsonFormat())
                || !"json_schema".equalsIgnoreCase(config.getJsonFormatType())) {
            return options;
        }
        if ("REQUIREMENTS".equalsIgnoreCase(stageLabel)) {
            return options.withJsonSchema("bank_request_contract", BankRequestContract.JSON_SCHEMA);
        }
        if ("PLAN".equalsIgnoreCase(stageLabel)) {
            return options.withJsonSchema("bank_query_plan", BankQueryPlan.JSON_SCHEMA);
        }
        return options;
    }

    /**
     * Accumulates per-call llama.cpp timing facts into counters. Only numeric whitelisted keys are
     * read; the raw timings map is never logged here.
     */
    private void recordLlamaCppTimings(LlamaCppPrefixChatClient.ChatResult result) {
        Map<String, Object> timings = result.timings();
        int cacheN = numberAsInt(timings.get("cache_n"));
        if (cacheN > 0) {
            llamaCppCacheHits.incrementAndGet();
            llamaCppCacheTokens.addAndGet(cacheN);
        }
        add(timings, "prompt_tokens", llamaCppPromptTokens);
        add(timings, "completion_tokens", llamaCppCompletionTokens);
        add(timings, "prompt_ms", llamaCppPromptMs);
        add(timings, "predicted_ms", llamaCppDecodeMs);
        int promptN = numberAsInt(timings.get("prompt_n"));
        KEY_PIPELINE.info(
                "FixedSystemPrefixLlmCache llama.cpp call stage={} version={} cacheN={} promptN={} promptTokens={} completionTokens={} promptMs={} decodeMs={}",
                stageLabel, prefixVersion, cacheN, promptN,
                timings.get("prompt_tokens"), timings.get("completion_tokens"),
                timings.get("prompt_ms"), timings.get("predicted_ms"));
    }

    private static void add(Map<String, Object> timings, String key, AtomicLong counter) {
        int value = numberAsInt(timings.get(key));
        if (value > 0) {
            counter.addAndGet(value);
        }
    }

    private static int numberAsInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String shortKey(String key) {
        int idx = key.lastIndexOf(':');
        String hash = idx >= 0 ? key.substring(idx + 1) : key;
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
