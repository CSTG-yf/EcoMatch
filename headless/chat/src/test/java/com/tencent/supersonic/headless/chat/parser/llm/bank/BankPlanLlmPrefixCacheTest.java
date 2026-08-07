package com.tencent.supersonic.headless.chat.parser.llm.bank;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankPlanLlmPrefixCacheTest {

    @Test
    void shouldReuseProcessLocalCompletionForIdenticalDynamicPayload() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{\"version\":\"1.0\",\"intent\":\"RANKING\"}");
        // autoWarm=false so unit tests only count real generate calls
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        String first = cache.generate(model, "用户问题：A", true);
        String second = cache.generate(model, "用户问题：A", true);

        assertEquals(first, second);
        verify(model, times(1)).generate(anyString());
        assertEquals(1L, cache.stats().get("completionHits"));
        assertEquals(1L, cache.stats().get("completionMisses"));
    }

    @Test
    void shouldKeepFixedSystemPrefixAtMessageHeadForKvReuse() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.startsWith(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX),
                    "prompt must start with fixed system prefix for KV reuse");
            assertTrue(prompt.contains("江苏省A市农商行存款余额是多少"));
            return "{}";
        });
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        cache.generate(model, "江苏省A市农商行存款余额是多少", false);

        verify(model, times(1)).generate(anyString());
    }
}
