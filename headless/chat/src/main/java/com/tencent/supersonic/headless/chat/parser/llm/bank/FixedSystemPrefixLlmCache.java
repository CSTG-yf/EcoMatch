package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<String> warmedModelKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong prefixWarmAttempts = new AtomicLong();
    private final AtomicLong prefixWarmVerificationFailures = new AtomicLong();
    private final AtomicLong completionHits = new AtomicLong();
    private final AtomicLong completionMisses = new AtomicLong();
    private final AtomicLong coalescedCompletionWaiters = new AtomicLong();
    private final AtomicLong modelCalls = new AtomicLong();
    private final AtomicLong providerCalls = new AtomicLong();
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
    private final Map<String, CompletableFuture<String>> inFlightCompletions =
            new ConcurrentHashMap<>();
    private final LlamaCppPrefixChatClient openAiCompatibleClient =
            new LlamaCppPrefixChatClient();

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

    public synchronized boolean warmPrefix(ChatLanguageModel model, ChatModelConfig config) {
        return warmPrefix(model, config, false);
    }

    /** Replays and verifies the fixed prefix when a new chat is created. */
    public synchronized boolean refreshPrefix(ChatLanguageModel model, ChatModelConfig config) {
        return warmPrefix(model, config, true);
    }

    private boolean warmPrefix(ChatLanguageModel model, ChatModelConfig config, boolean force) {
        boolean llamaCpp = usesLlamaCppPrefixTransport(config);
        if (!autoWarm && !llamaCpp) {
            return false;
        }
        String modelKey = modelIdentity(config);
        if (!force && warmedModelKeys.contains(modelKey)) {
            return true;
        }
        prefixWarmAttempts.incrementAndGet();
        try {
            long cacheHitsBefore = llamaCppCacheHits.get();
            callModel(model, config, warmUserProbe,
                    LlamaCppPrefixChatClient.ChatOptions.warmup(enableThinking));
            if (llamaCpp) {
                // The first request populates llama.cpp's KV cache. The second identical probe is
                // the acceptance check: only a reported cache_n hit counts as warmed.
                callModel(model, config, warmUserProbe,
                        LlamaCppPrefixChatClient.ChatOptions.warmup(enableThinking));
                if (llamaCppCacheHits.get() <= cacheHitsBefore) {
                    throw new IllegalStateException(
                            "llama.cpp warm-up verification returned no cache_n hit");
                }
            }
            warmedModelKeys.add(modelKey);
            KEY_PIPELINE.info(
                    "FixedSystemPrefixLlmCache verified fixed system prefix version={} via={} force={}",
                    prefixVersion, llamaCpp ? "llama.cpp" : "langchain4j", force);
            return true;
        } catch (RuntimeException ex) {
            warmedModelKeys.remove(modelKey);
            prefixWarmVerificationFailures.incrementAndGet();
            LOG.warn("Fixed system prefix warm-up failed version={}: type={}, error=[{}]",
                    prefixVersion, ex.getClass().getSimpleName(),
                    ex.getMessage() == null ? ""
                            : ex.getMessage().substring(0, Math.min(160, ex.getMessage().length())));
            return false;
        }
    }

    public boolean warmPrefix(ChatLanguageModel model) {
        return warmPrefix(model, null);
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
            throw new IllegalArgumentException("chat model or configured baseUrl is required");
        }
        warmPrefix(model, config);

        String memoKey = memoKey(config, dynamicUserContent);
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

        if (useMemo) {
            CompletableFuture<String> mine = new CompletableFuture<>();
            CompletableFuture<String> existing = inFlightCompletions.putIfAbsent(memoKey, mine);
            if (existing != null) {
                coalescedCompletionWaiters.incrementAndGet();
                return awaitCompletion(existing);
            }
            try {
                String text = callAndMemoize(model, config, dynamicUserContent, memoKey);
                mine.complete(text);
                return text;
            } catch (RuntimeException ex) {
                mine.completeExceptionally(ex);
                throw ex;
            } finally {
                inFlightCompletions.remove(memoKey, mine);
            }
        }
        return callAndMemoize(model, config, dynamicUserContent, null);
    }

    private String callAndMemoize(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent, String memoKey) {
        modelCalls.incrementAndGet();
        String text = callModel(model, config, dynamicUserContent);
        if (memoKey != null && text != null && !text.isBlank()) {
            completionMemo.put(memoKey, text);
        }
        KEY_PIPELINE.info(
                "FixedSystemPrefixLlmCache completion MISS prefixVersion={} modelCalls={} memoSize={} llamaCppCacheHits={}",
                prefixVersion, modelCalls.get(), completionMemo.size(), llamaCppCacheHits.get());
        return text;
    }

    private String awaitCompletion(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    /**
     * Drops the memoized completion for this user content so a poisoned entry (e.g. a
     * truncated model response that fails JSON parsing) is never replayed to later requests;
     * the next caller rolls a fresh sample instead. Semantically invalid but well-formed
     * responses should stay memoized — structured repair converges from them deterministically.
     */
    public void evictCompletion(ChatModelConfig config, String dynamicUserContent) {
        if (dynamicUserContent == null) {
            return;
        }
        String removed = completionMemo.remove(memoKey(config, dynamicUserContent));
        if (removed != null) {
            KEY_PIPELINE.info(
                    "FixedSystemPrefixLlmCache completion EVICT prefixVersion={} memoSize={}",
                    prefixVersion, completionMemo.size());
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stage", stageLabel);
        stats.put("prefixVersion", prefixVersion);
        stats.put("prefixWarmed", !warmedModelKeys.isEmpty());
        stats.put("prefixWarmModelCount", warmedModelKeys.size());
        stats.put("autoWarm", autoWarm);
        stats.put("prefixWarmAttempts", prefixWarmAttempts.get());
        stats.put("prefixWarmVerificationFailures", prefixWarmVerificationFailures.get());
        stats.put("completionHits", completionHits.get());
        stats.put("completionMisses", completionMisses.get());
        stats.put("coalescedCompletionWaiters", coalescedCompletionWaiters.get());
        stats.put("modelCalls", modelCalls.get());
        stats.put("providerCalls", providerCalls.get());
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
        return memoKey(null, dynamicUserContent);
    }

    String memoKey(ChatModelConfig config, String dynamicUserContent) {
        return prefixVersion + ":" + modelIdentity(config) + ":" + sha256(dynamicUserContent);
    }

    /**
     * Resolves the llama.cpp chat options for one call: an explicit request (warm-up or thinking)
     * wins; otherwise thinking wins when enabled; otherwise a safety cap bounds the decode. The
     * cap is always explicit for local llama.cpp endpoints and for remote endpoints whose model
     * config carries a bounded reasoning budget ({@code reasoningEffort}); remote reasoning
     * endpoints without that bound stay uncapped because server-side reasoning tokens would
     * silently consume an implicit decode budget and truncate the returned JSON. Uncapped remote
     * calls fall back to the provider default, which truncated long plan JSON in official runs
     * (613/1114-token MALFORMED_JSON), so bounded-reasoning endpoints must send the cap.
     */
    LlamaCppPrefixChatClient.ChatOptions resolveOptions(ChatModelConfig config,
            LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        if (requestedOptions != null) {
            return requestedOptions;
        }
        if (enableThinking) {
            return LlamaCppPrefixChatClient.ChatOptions.thinking(thinkingMaxTokens);
        }
        String baseUrl = config == null ? null : config.getBaseUrl();
        if (safetyMaxTokens > 0
                && (LlamaCppPrefixChatClient.usesLlamaCppExtensions(baseUrl)
                        || hasBoundedRemoteReasoning(config))) {
            return LlamaCppPrefixChatClient.ChatOptions.safetyCap(safetyMaxTokens);
        }
        return LlamaCppPrefixChatClient.ChatOptions.defaults();
    }

    private static boolean hasBoundedRemoteReasoning(ChatModelConfig config) {
        return config != null && StringUtils.isNotBlank(config.getReasoningEffort());
    }

    /** Test/legacy bridge: baseUrl-only resolution with no model-level reasoning bound. */
    LlamaCppPrefixChatClient.ChatOptions resolveOptions(String baseUrl,
            LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        ChatModelConfig config = null;
        if (baseUrl != null) {
            config = new ChatModelConfig();
            config.setBaseUrl(baseUrl);
        }
        return resolveOptions(config, requestedOptions);
    }

    /** Legacy single-arg overload; resolves without endpoint or reasoning-bound knowledge. */
    LlamaCppPrefixChatClient.ChatOptions resolveOptions(
            LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        return resolveOptions((ChatModelConfig) null, requestedOptions);
    }

    static boolean usesLlamaCppPrefixTransport(ChatModelConfig config) {
        return config != null && StringUtils.isNotBlank(config.getBaseUrl())
                && "OPEN_AI".equalsIgnoreCase(config.getProvider())
                && LlamaCppPrefixChatClient.usesLlamaCppExtensions(config.getBaseUrl());
    }

    /**
     * Uses the direct OpenAI-compatible transport when a structured stage must attach its exact
     * JSON Schema. Local endpoints additionally receive llama.cpp extensions; public endpoints do
     * not. Other providers continue through their registered {@link ChatLanguageModel}.
     */
    static boolean usesOpenAiStructuredTransport(ChatModelConfig config) {
        return config != null && StringUtils.isNotBlank(config.getBaseUrl())
                && "OPEN_AI".equalsIgnoreCase(config.getProvider());
    }

    private static String modelIdentity(ChatModelConfig config) {
        if (config == null) {
            return sha256("provider-default");
        }
        String provider = StringUtils.defaultString(config.getProvider()).strip()
                .toUpperCase(Locale.ROOT);
        String baseUrl = StringUtils.defaultString(config.getBaseUrl()).strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String modelName = StringUtils.defaultString(config.getModelName()).strip();
        return sha256(provider + "\n" + baseUrl + "\n" + modelName);
    }

    private String callModel(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent) {
        return callModel(model, config, dynamicUserContent, null);
    }

    private String callModel(ChatLanguageModel model, ChatModelConfig config,
            String dynamicUserContent, LlamaCppPrefixChatClient.ChatOptions requestedOptions) {
        boolean llamaCpp = usesLlamaCppPrefixTransport(config);
        LlamaCppPrefixChatClient.ChatOptions options = bindStageSchema(config,
                resolveOptions(config, requestedOptions));
        // Every OpenAI-compatible remote goes through our direct client so configured knobs such
        // as reasoning_effort reach the wire even without a bound response schema.
        boolean directOpenAiCompatible = !llamaCpp && usesOpenAiStructuredTransport(config);
        if (llamaCpp || directOpenAiCompatible) {
            try {
                LlamaCppPrefixChatClient.ChatResult result =
                        openAiCompatibleClient.chat(config, systemPrefix, dynamicUserContent,
                                options);
                if (llamaCpp) {
                    llamaCppCalls.incrementAndGet();
                    recordLlamaCppTimings(result);
                } else {
                    providerCalls.incrementAndGet();
                }
                return result.content();
            } catch (RuntimeException ex) {
                if (!llamaCpp || !shouldFallbackToLangchain(ex)) {
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
            throw new IllegalStateException("no standard provider chat model is available");
        }
        providerCalls.incrementAndGet();
        return model.generate(composeFullPrompt(dynamicUserContent));
    }

    static boolean shouldFallbackToLangchain(RuntimeException exception) {
        return !(exception instanceof LlamaCppPrefixChatClient.JsonSchemaCapabilityException);
    }

    LlamaCppPrefixChatClient.ChatOptions bindStageSchema(ChatModelConfig config,
            LlamaCppPrefixChatClient.ChatOptions options) {
        if (options.enableThinking() || options.omitResponseFormat()
                || config == null
                || !Boolean.TRUE.equals(config.getJsonFormat())
                || !"json_schema".equalsIgnoreCase(config.getJsonFormatType())) {
            return options;
        }
        if ("SINGLE_PASS".equalsIgnoreCase(stageLabel)) {
            return options.withJsonSchema("bank_planning_response",
                    BankPlanningResponse.JSON_SCHEMA);
        }
        if ("REQUIREMENTS".equalsIgnoreCase(stageLabel)) {
            return options.withJsonSchema("bank_request_contract",
                    BankRequestContract.JSON_SCHEMA);
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
