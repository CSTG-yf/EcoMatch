package com.tencent.supersonic.headless.chat.parser.llm.bank;

import dev.langchain4j.model.chat.ChatLanguageModel;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedSystemPrefixLlmCacheTest {

    @Test
    void coalescesConcurrentIdenticalCompletionMisses() throws Exception {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "{}";
        });
        FixedSystemPrefixLlmCache cache =
                new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> cache.generate(model, "同一问题", true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> cache.generate(model, "同一问题", true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (((Long) cache.stats().get("coalescedCompletionWaiters")) == 0L
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1L, cache.stats().get("coalescedCompletionWaiters"));
            release.countDown();

            assertEquals("{}", first.get(5, TimeUnit.SECONDS));
            assertEquals("{}", second.get(5, TimeUnit.SECONDS));
            verify(model, times(1)).generate(anyString());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void planPrefixStartsPromptWithFixedSystem() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.startsWith(BankPlanPromptComposer.SINGLE_PASS_SYSTEM_PREFIX));
            assertTrue(prompt.contains("\n\n用户问题\n\n<stage>SINGLE_PASS</stage>"));
            assertTrue(prompt.indexOf("唯一正常阶段：SINGLE_PASS")
                    < prompt.indexOf("\n\n用户问题"));
            return "{}";
        });
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache(
                BankPlanPromptComposer.SINGLE_PASS_SYSTEM_PREFIX,
                BankPlanPromptComposer.SINGLE_PASS_PREFIX_VERSION, 32, false);

        String user = BankPlanPromptComposer.buildSinglePassUserContent("用户问题");
        cache.generate(model, user, false);
        verify(model, times(1)).generate(anyString());
    }

    @Test
    void memoReusesIdenticalUserPayload() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{}");
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache(
                BankPlanPromptComposer.SINGLE_PASS_SYSTEM_PREFIX,
                BankPlanPromptComposer.SINGLE_PASS_PREFIX_VERSION, 32, false);

        String user = BankPlanPromptComposer.buildSinglePassUserContent("q");
        assertEquals(cache.generate(model, user, true), cache.generate(model, user, true));
        verify(model, times(1)).generate(anyString());
        assertEquals(1L, cache.stats().get("completionHits"));
    }

    @Test
    void evictCompletionForcesAFreshRollForPoisonedMemoEntries() {
        // 截断等 MALFORMED 响应若被 memo 固化，后续同题会毫秒级原样重放并污染修复轮；
        // 逐出后必须真正重掷一次模型采样。
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{\"truncated\":");
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache(
                BankPlanPromptComposer.SINGLE_PASS_SYSTEM_PREFIX,
                BankPlanPromptComposer.SINGLE_PASS_PREFIX_VERSION, 32, false);

        String user = BankPlanPromptComposer.buildSinglePassUserContent("q");
        cache.generate(model, user, true);
        cache.evictCompletion(null, user);
        cache.generate(model, user, true);

        verify(model, times(2)).generate(anyString());
        assertEquals(0L, cache.stats().get("completionHits"));
    }

    @Test
    void stageLabelAndSafetyCapAreExposedInStatsAndOptions() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "REQUIREMENTS", 768);

        assertEquals("REQUIREMENTS", cache.stats().get("stage"));
        assertEquals(768, cache.stats().get("safetyMaxTokens"));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://127.0.0.1:8080", null));
        assertWarmup(cache.resolveOptions("http://127.0.0.1:8080",
                LlamaCppPrefixChatClient.ChatOptions.warmup(false)), false, 1);
    }

    @Test
    void localLoopbackEndpointRetainsSafetyCap() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "REQUIREMENTS", 768);

        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://127.0.0.1:8080", null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://localhost:8080", null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://127.0.0.1:8080/v1/chat/completions", null));
    }

    @Test
    void privateRfc1918EndpointRetainsSafetyCap() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "REQUIREMENTS", 768);

        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://10.0.0.5:8080", null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://172.16.0.10:8080", null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://192.168.1.20:8080", null));
    }

    @Test
    void remoteHttpsEndpointGetsNoImplicitSafetyCap() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "REQUIREMENTS", 768);

        assertEquals(LlamaCppPrefixChatClient.ChatOptions.defaults(),
                cache.resolveOptions("https://api.deepseek.com/v1", null),
                "remote HTTPS endpoints must not receive an implicit max_tokens safety cap");
        assertEquals(LlamaCppPrefixChatClient.ChatOptions.defaults(),
                cache.resolveOptions("https://api.openai.com/v1", null));
        assertEquals(LlamaCppPrefixChatClient.ChatOptions.defaults(),
                cache.resolveOptions("https://gateway.example.com/v1/chat/completions", null));
    }

    @Test
    void remoteEndpointWithBoundedReasoningEffortGetsExplicitDecodeCap() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "SINGLE_PASS", 2048);

        ChatModelConfig bounded = new ChatModelConfig();
        bounded.setBaseUrl("https://www.autodl.art/api/v1");
        bounded.setReasoningEffort("low");
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 2048),
                cache.resolveOptions(bounded, null),
                "remote endpoints with an explicit reasoning budget must send the decode cap "
                        + "instead of trusting the provider default (plan-JSON truncation)");
    }

    @Test
    void remoteEndpointWithoutReasoningBoundKeepsLegacyNoCapProtection() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "SINGLE_PASS", 2048);

        ChatModelConfig implicitReasoning = new ChatModelConfig();
        implicitReasoning.setBaseUrl("https://api.deepseek.com/v1");
        assertEquals(LlamaCppPrefixChatClient.ChatOptions.defaults(),
                cache.resolveOptions(implicitReasoning, null),
                "implicit-reasoning remote endpoints stay uncapped: reasoning_content would "
                        + "consume an implicit decode budget and truncate the JSON");
    }

    @Test
    void explicitWarmupAndThinkingWinRegardlessOfEndpoint() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "PLAN", 1024);

        assertWarmup(cache.resolveOptions("https://api.deepseek.com/v1",
                LlamaCppPrefixChatClient.ChatOptions.warmup(false)), false, 1);
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(true, 2048),
                cache.resolveOptions("https://api.deepseek.com/v1",
                        LlamaCppPrefixChatClient.ChatOptions.thinking(2048)),
                "explicit thinking must win on remote endpoints");
        assertWarmup(cache.resolveOptions("http://127.0.0.1:8080",
                LlamaCppPrefixChatClient.ChatOptions.warmup(false)), false, 1);
    }

    @Test
    void explicitThinkingWinsOverSafetyCapOnLocalEndpoint() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "PLAN", 1024);

        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(true, 4096),
                cache.resolveOptions("http://127.0.0.1:8080",
                        LlamaCppPrefixChatClient.ChatOptions.thinking(4096)),
                "explicit thinking must win over the local safety cap");
    }

    @Test
    void thinkingModeAppliesAcrossEndpointsWhenEnabled() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", true, 8192, "PLAN", 1024);

        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(true, 8192),
                cache.resolveOptions("http://127.0.0.1:8080", null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(true, 8192),
                cache.resolveOptions("https://api.deepseek.com/v1", null),
                "thinking mode (explicit configuration) must apply on remote endpoints too");
    }

    @Test
    void perCallTokenAndTimingCountersAreExposedEvenWithoutLlamaCpp() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{}");
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "PLAN", 0);

        cache.generate(model, "问题", false);

        assertTrue(cache.stats().containsKey("llamaCppPromptTokens"));
        assertTrue(cache.stats().containsKey("llamaCppCompletionTokens"));
        assertTrue(cache.stats().containsKey("llamaCppPromptMs"));
        assertTrue(cache.stats().containsKey("llamaCppDecodeMs"));
        assertEquals(0L, cache.stats().get("llamaCppPromptTokens"));
    }

    @Test
    void memoKeyBindsPrefixVersionToDynamicContent() {
        FixedSystemPrefixLlmCache cache =
                new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32, false);

        String key = cache.memoKey("同一动态内容");

        assertTrue(key.startsWith("v-test:"));
        assertEquals(64, key.substring(key.lastIndexOf(':') + 1).length(),
                "dynamic part must be a sha-256 hex digest");
        assertFalse(key.contains("同一动态内容"),
                "memo keys must never embed raw user content");
    }

    @Test
    void transportSelectionUsesLocalOpenAiEndpointOnlyForLlamaCppPrefix() {
        ChatModelConfig local = modelConfig("OPEN_AI", "http://192.168.20.115:8080/v1",
                "local-qwen");
        ChatModelConfig cloud = modelConfig("OPEN_AI", "https://cloud.example.com/v1",
                "cloud-model");
        ChatModelConfig ollama = modelConfig("OLLAMA", "http://127.0.0.1:11434",
                "local-ollama");

        assertTrue(FixedSystemPrefixLlmCache.usesLlamaCppPrefixTransport(local));
        assertFalse(FixedSystemPrefixLlmCache.usesLlamaCppPrefixTransport(cloud));
        assertFalse(FixedSystemPrefixLlmCache.usesLlamaCppPrefixTransport(ollama));
    }

    @Test
    void memoKeySeparatesModelsForTheSameQuestion() {
        FixedSystemPrefixLlmCache cache =
                new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32, false);
        ChatModelConfig local = modelConfig("OPEN_AI", "http://192.168.20.115:8080/v1",
                "local-qwen");
        ChatModelConfig cloud = modelConfig("OPEN_AI", "https://cloud.example.com/v1",
                "cloud-model");

        assertFalse(cache.memoKey(local, "同一问题").equals(cache.memoKey(cloud, "同一问题")));
        assertFalse(cache.memoKey(local, "同一问题").contains("local-qwen"));
        assertFalse(cache.memoKey(cloud, "同一问题").contains("cloud.example.com"));
    }

    @Test
    void structuredStagesBindTheirOwnPublishedContractSchema() {
        ChatModelConfig config = new ChatModelConfig();
        config.setJsonFormat(true);
        config.setJsonFormatType("json_schema");
        FixedSystemPrefixLlmCache requirements = new FixedSystemPrefixLlmCache("系统前缀", "v", 32,
                false, "预热", false, 0, "REQUIREMENTS", 768);
        FixedSystemPrefixLlmCache plan = new FixedSystemPrefixLlmCache("系统前缀", "v", 32, false,
                "预热", false, 0, "PLAN", 1024);

        LlamaCppPrefixChatClient.ChatOptions requirementOptions =
                requirements.bindStageSchema(config, LlamaCppPrefixChatClient.ChatOptions.defaults());
        LlamaCppPrefixChatClient.ChatOptions planOptions =
                plan.bindStageSchema(config, LlamaCppPrefixChatClient.ChatOptions.defaults());

        assertEquals("bank_request_contract", requirementOptions.jsonSchemaName());
        assertEquals(BankRequestContract.JSON_SCHEMA, requirementOptions.jsonSchema());
        assertEquals("bank_query_plan", planOptions.jsonSchemaName());
        assertEquals(BankQueryPlan.JSON_SCHEMA, planOptions.jsonSchema());
    }

    @Test
    void schemaCapabilityFailureNeverFallsBackToAnotherModelPath() {
        assertFalse(FixedSystemPrefixLlmCache.shouldFallbackToLangchain(
                LlamaCppPrefixChatClient.JsonSchemaCapabilityException.unexpectedStatus(500)));
        assertTrue(FixedSystemPrefixLlmCache.shouldFallbackToLangchain(
                new IllegalStateException("ordinary transport failure")));
    }

    private static void assertWarmup(LlamaCppPrefixChatClient.ChatOptions options,
            boolean thinking, int maximum) {
        assertEquals(thinking, options.enableThinking());
        assertEquals(maximum, options.maxTokens());
        assertTrue(options.omitResponseFormat());
    }

    private static ChatModelConfig modelConfig(String provider, String baseUrl,
            String modelName) {
        ChatModelConfig config = new ChatModelConfig();
        config.setProvider(provider);
        config.setBaseUrl(baseUrl);
        config.setModelName(modelName);
        return config;
    }
}
