package com.tencent.supersonic.headless.chat.parser.llm.bank;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankPlanLlmPrefixCacheTest {

    @Test
    void shouldReuseProcessLocalCompletionForIdenticalStageAndPayload() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{\"version\":\"1.0\",\"intent\":\"RANKING\"}");
        // autoWarm=false so unit tests only count real generate calls
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        String first = cache.generate(model, BankPlanLlmPrefixCache.Stage.REQUIREMENTS,
                "用户问题：A", true);
        String second = cache.generate(model, BankPlanLlmPrefixCache.Stage.REQUIREMENTS,
                "用户问题：A", true);

        assertEquals(first, second);
        verify(model, times(1)).generate(anyString());
        assertEquals(1L, cache.stats().get("completionHits"));
        assertEquals(1L, cache.stats().get("completionMisses"));
        assertEquals(1L, cache.stats().get("modelCalls"));
    }

    @Test
    void differentStagesNeverReuseEachOthersCompletionMemo() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{}");
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        cache.generate(model, BankPlanLlmPrefixCache.Stage.REQUIREMENTS, "相同用户输入", true);
        cache.generate(model, BankPlanLlmPrefixCache.Stage.PLAN, "相同用户输入", true);
        cache.generate(model, BankPlanLlmPrefixCache.Stage.REQUIREMENTS, "相同用户输入", true);

        verify(model, times(2)).generate(anyString());
        assertEquals(1L, cache.stats().get("completionHits"),
                "only the repeated REQUIREMENTS call may hit its own stage memo");
        assertEquals(2L, cache.stats().get("completionMisses"),
                "the PLAN call with identical text must never reuse the REQUIREMENTS memo");
    }

    @Test
    void eachStageKeepsItsOwnSystemPrefixAtTheMessageHeadForKvReuse() {
        String requirements = new BankPlanLlmPrefixCache(32, false)
                .composeFullPrompt(BankPlanLlmPrefixCache.Stage.REQUIREMENTS, "用户问题");
        String plan = new BankPlanLlmPrefixCache(32, false)
                .composeFullPrompt(BankPlanLlmPrefixCache.Stage.PLAN, "用户问题");

        assertTrue(requirements.startsWith(BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX),
                "requirements prompt must start with the requirements prefix for KV reuse");
        assertTrue(plan.startsWith(BankPlanPromptComposer.PLAN_SYSTEM_PREFIX),
                "plan prompt must start with the plan prefix for KV reuse");
        assertTrue(requirements.endsWith("用户问题"));
        assertTrue(plan.endsWith("用户问题"));
    }

    @Test
    void requirementsCallsUseOnlyTheRequirementsPrefix() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.startsWith(BankPlanPromptComposer.REQUIREMENTS_SYSTEM_PREFIX),
                    "requirements prompt must start with the requirements prefix");
            assertTrue(prompt.contains("第一阶段：REQUIREMENTS 的精确输出格式"));
            assertFalse(prompt.contains("第二阶段：PLAN 的精确输出格式"));
            return "{}";
        });
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        cache.generate(model, BankPlanLlmPrefixCache.Stage.REQUIREMENTS, "问题", false);
        verify(model, times(1)).generate(anyString());
    }

    @Test
    void planCallsUseOnlyThePlanPrefix() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.startsWith(BankPlanPromptComposer.PLAN_SYSTEM_PREFIX),
                    "plan prompt must start with the plan prefix");
            assertTrue(prompt.contains("第二阶段：PLAN 的精确输出格式"));
            assertFalse(prompt.contains("第一阶段：REQUIREMENTS 的精确输出格式"));
            return "{}";
        });
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        cache.generate(model, BankPlanLlmPrefixCache.Stage.PLAN, "问题", false);
        verify(model, times(1)).generate(anyString());
    }

    @Test
    void memoKeysBindStageAndPrefixVersion() {
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        String requirementsKey =
                cache.memoKey(BankPlanLlmPrefixCache.Stage.REQUIREMENTS, "同一动态内容");
        String planKey = cache.memoKey(BankPlanLlmPrefixCache.Stage.PLAN, "同一动态内容");

        assertNotEquals(requirementsKey, planKey);
        assertTrue(requirementsKey.startsWith(BankPlanPromptComposer.REQUIREMENTS_PREFIX_VERSION));
        assertTrue(planKey.startsWith(BankPlanPromptComposer.PLAN_PREFIX_VERSION));
    }

    @Test
    void outputSafetyCapsStayAboveObservedPerStageMaxima() {
        assertTrue(BankPlanLlmPrefixCache.REQUIREMENTS_MAX_OUTPUT_TOKENS > 342,
                "requirements cap must exceed the observed 342 token maximum");
        assertTrue(BankPlanLlmPrefixCache.PLAN_MAX_OUTPUT_TOKENS > 457,
                "plan cap must exceed the observed 457 token maximum");
        assertTrue(BankPlanLlmPrefixCache.PLAN_MAX_OUTPUT_TOKENS > 593,
                "PLAN_MAX_OUTPUT_TOKENS also bounds PLAN repair calls above the 593 token repair maximum");
        assertTrue(BankPlanLlmPrefixCache.REQUIREMENTS_MAX_OUTPUT_TOKENS
                < BankPlanLlmPrefixCache.PLAN_MAX_OUTPUT_TOKENS);
    }

    @Test
    void statsExposePerStageCountersWithoutMergingThem() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("{}");
        BankPlanLlmPrefixCache cache = new BankPlanLlmPrefixCache(32, false);

        cache.generate(model, BankPlanLlmPrefixCache.Stage.REQUIREMENTS, "R", false);
        cache.generate(model, BankPlanLlmPrefixCache.Stage.PLAN, "P", false);

        assertTrue(cache.stats().containsKey("requirements"));
        assertTrue(cache.stats().containsKey("plan"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> requirements =
                (java.util.Map<String, Object>) cache.stats().get("requirements");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> plan =
                (java.util.Map<String, Object>) cache.stats().get("plan");
        assertEquals(1L, requirements.get("modelCalls"));
        assertEquals(1L, plan.get("modelCalls"));
        assertEquals(2L, cache.stats().get("modelCalls"));
    }
}
