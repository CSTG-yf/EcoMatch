package com.tencent.supersonic.headless.chat.parser.llm.bank;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

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
    void freeSqlPrefixStartsPromptWithFixedSystem() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.contains("各项存款余额"));
            assertTrue(prompt.contains("\n\n存款\n\n附加信息：I"));
            assertTrue(prompt.indexOf("各项存款余额") < prompt.indexOf("\n\n存款"));
            return "{\"sql\":\"SELECT 1\"}";
        });
        String system = BankFreeSqlPromptComposer.composeSystemPrefix(
                "Table=[银行], Metrics=[<各项存款余额>], Dimensions=[<机构>]");
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache(system,
                BankFreeSqlPromptComposer.prefixVersion("Table=[银行]"), 32, false);

        String user = BankFreeSqlPromptComposer.buildQuestionOnlyUserContent("存款", "I", "");
        cache.generate(model, user, false);
        verify(model, times(1)).generate(anyString());
    }

    @Test
    void memoReusesIdenticalUserPayload() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{\"sql\":\"SELECT 1\"}");
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache(
                BankFreeSqlPromptComposer.composeSystemPrefix("Table=[t]"),
                BankFreeSqlPromptComposer.PROMPT_VERSION, 32, false);

        String user = BankFreeSqlPromptComposer.buildQuestionOnlyUserContent("q", "I", "");
        assertEquals(cache.generate(model, user, true), cache.generate(model, user, true));
        verify(model, times(1)).generate(anyString());
        assertEquals(1L, cache.stats().get("completionHits"));
    }

    @Test
    void stageLabelAndSafetyCapAreExposedInStatsAndOptions() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "REQUIREMENTS", 768);

        assertEquals("REQUIREMENTS", cache.stats().get("stage"));
        assertEquals(768, cache.stats().get("safetyMaxTokens"));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 768),
                cache.resolveOptions("http://127.0.0.1:8080", null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 1),
                cache.resolveOptions("http://127.0.0.1:8080",
                        LlamaCppPrefixChatClient.ChatOptions.warmup(false)),
                "explicit requests (warm-up) must win over the safety cap");
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
    void explicitWarmupAndThinkingWinRegardlessOfEndpoint() {
        FixedSystemPrefixLlmCache cache = new FixedSystemPrefixLlmCache("系统前缀", "v-test", 32,
                false, "预热", false, 0, "PLAN", 1024);

        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 1),
                cache.resolveOptions("https://api.deepseek.com/v1",
                        LlamaCppPrefixChatClient.ChatOptions.warmup(false)),
                "explicit warm-up must win on remote endpoints");
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(true, 2048),
                cache.resolveOptions("https://api.deepseek.com/v1",
                        LlamaCppPrefixChatClient.ChatOptions.thinking(2048)),
                "explicit thinking must win on remote endpoints");
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 1),
                cache.resolveOptions("http://127.0.0.1:8080",
                        LlamaCppPrefixChatClient.ChatOptions.warmup(false)),
                "explicit warm-up must win on local endpoints");
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
}
