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

class FixedSystemPrefixLlmCacheTest {

    @Test
    void freeSqlPrefixStartsPromptWithFixedSystem() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.contains("各项存款余额"));
            assertTrue(prompt.contains("Question:存款"));
            assertTrue(prompt.indexOf("各项存款余额") < prompt.indexOf("Question:存款"));
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
}
