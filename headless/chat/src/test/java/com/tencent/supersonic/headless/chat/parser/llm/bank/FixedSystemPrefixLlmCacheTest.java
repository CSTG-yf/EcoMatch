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
                cache.resolveOptions(null));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 1),
                cache.resolveOptions(LlamaCppPrefixChatClient.ChatOptions.warmup(false)),
                "explicit requests (warm-up) must win over the safety cap");
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
