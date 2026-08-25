package com.tencent.supersonic.headless.chat.parser.llm.bank;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankPlanLlmPrefixCacheTest {

    @Test
    void shouldReuseProcessLocalCompletionForIdenticalSinglePassPayload() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{}");
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        String first = cache.generate(model, BankPlanLlmPrefixCache.Stage.SINGLE_PASS,
                "用户问题：A", true);
        String second = cache.generate(model, BankPlanLlmPrefixCache.Stage.SINGLE_PASS,
                "用户问题：A", true);

        assertEquals(first, second);
        verify(model, times(1)).generate(anyString());
        assertEquals(1L, cache.stats().get("completionHits"));
        assertEquals(1L, cache.stats().get("completionMisses"));
        assertEquals(1L, cache.stats().get("modelCalls"));
    }

    @Test
    void promptUsesOnePrefixContainingBothNestedContracts() {
        String prompt = new BankPlanLlmPrefixCache(32, false).composeFullPrompt(
                BankPlanLlmPrefixCache.Stage.SINGLE_PASS, "用户问题");

        assertTrue(prompt.startsWith(BankPlanPromptComposer.SINGLE_PASS_SYSTEM_PREFIX));
        assertTrue(prompt.contains("唯一正常阶段：SINGLE_PASS"));
        assertTrue(prompt.contains("requirements"));
        assertTrue(prompt.contains("plan"));
        assertTrue(prompt.endsWith("用户问题"));
        assertFalse(prompt.contains("不得先输出中间需求再等待第二次调用。\n\n用户问题"));
    }

    @Test
    void memoKeyBindsSinglePassPrefixVersion() {
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);
        String key = cache.memoKey(BankPlanLlmPrefixCache.Stage.SINGLE_PASS, "动态内容");
        assertTrue(key.startsWith(BankPlanPromptComposer.SINGLE_PASS_PREFIX_VERSION));
    }

    @Test
    void outputSafetyCapFitsCombinedResponse() {
        assertTrue(BankPlanLlmPrefixCache.SINGLE_PASS_MAX_OUTPUT_TOKENS > 1200);
    }

    @Test
    void statsExposeOnlySinglePassCounters() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{}");
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        cache.generate(model, BankPlanLlmPrefixCache.Stage.SINGLE_PASS, "R", false);

        assertTrue(cache.stats().containsKey("singlePass"));
        assertFalse(cache.stats().containsKey("requirements"));
        assertFalse(cache.stats().containsKey("plan"));
        @SuppressWarnings("unchecked")
        Map<String, Object> singlePass = (Map<String, Object>) cache.stats().get("singlePass");
        assertEquals(1L, singlePass.get("modelCalls"));
        assertEquals(1L, cache.stats().get("modelCalls"));
    }
}
