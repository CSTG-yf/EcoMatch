package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppPrefixChatClientTest {

    @Test
    void stripThinkingRemovesThinkBlocks() {
        String raw = "<think>\n先分析机构再写SQL\n</think>\n{\"thought\":\"ok\",\"sql\":\"SELECT 1\"}";
        String stripped = LlamaCppPrefixChatClient.stripThinking(raw);
        assertFalse(stripped.contains("先分析"));
        assertTrue(stripped.contains("SELECT 1"));
        assertEquals("SELECT 1", BankFreeSqlPromptComposer.extractSql(raw));
    }

    @Test
    void stripThinkingKeepsPlainJson() {
        String raw = "{\"thought\":\"t\",\"sql\":\"SELECT 2\"}";
        assertEquals(raw, LlamaCppPrefixChatClient.stripThinking(raw));
        assertEquals("SELECT 2", BankFreeSqlPromptComposer.extractSql(raw));
    }

    @Test
    void warmupUsesMinimalCompletionWithoutChangingThinkingMode() {
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(false, 1),
                LlamaCppPrefixChatClient.ChatOptions.warmup(false));
        assertEquals(new LlamaCppPrefixChatClient.ChatOptions(true, 1024),
                LlamaCppPrefixChatClient.ChatOptions.warmup(true));
    }

    @Test
    void nonThinkingRequestsExplicitlyDisableQwenReasoningAndKeepJsonFormat() {
        ObjectNode body = new ObjectMapper().createObjectNode();
        ChatModelConfig config = new ChatModelConfig();
        config.setJsonFormat(true);
        config.setJsonFormatType("json_object");

        LlamaCppPrefixChatClient.applyThinkingOptions(body, config,
                LlamaCppPrefixChatClient.ChatOptions.defaults());

        assertFalse(body.get("enable_thinking").asBoolean());
        assertFalse(body.path("chat_template_kwargs").get("enable_thinking").asBoolean());
        assertEquals("json_object", body.path("response_format").path("type").asText());
    }

    @Test
    void thinkingRequestsEnableReasoningAndDoNotForceJsonFormat() {
        ObjectNode body = new ObjectMapper().createObjectNode();
        ChatModelConfig config = new ChatModelConfig();
        config.setJsonFormat(true);

        LlamaCppPrefixChatClient.applyThinkingOptions(body, config,
                LlamaCppPrefixChatClient.ChatOptions.thinking(1024));

        assertTrue(body.get("enable_thinking").asBoolean());
        assertTrue(body.path("chat_template_kwargs").get("enable_thinking").asBoolean());
        assertTrue(body.get("response_format") == null);
    }

    @Test
    void parseResponseExtractsOnlyWhitelistedTimingsAndUsage() throws Exception {
        String body = """
                {"choices":[{"message":{"content":"{\\"plan\\":1}",
                "reasoning_content":"短推理"}}],
                "timings":{"prompt_n":21,"cache_n":18,"prompt_ms":120.5,"predicted_ms":330.2,
                "prompt_per_second":40.1,"predicted_per_second":12.3,
                "unexpected_text_key":"must not leak through"},
                "usage":{"prompt_tokens":21,"completion_tokens":7}}
                """;

        LlamaCppPrefixChatClient.ChatResult result =
                new LlamaCppPrefixChatClient().parseResponse(body, true);

        assertEquals("{\"plan\":1}", result.content());
        assertTrue(result.thinkingEnabled());
        assertTrue(result.reasoningChars() > 0);
        assertTrue(result.timings().containsKey("prompt_n"));
        assertTrue(result.timings().containsKey("cache_n"));
        assertTrue(result.timings().containsKey("prompt_ms"));
        assertTrue(result.timings().containsKey("predicted_ms"));
        assertTrue(result.timings().containsKey("prompt_tokens"));
        assertTrue(result.timings().containsKey("completion_tokens"));
        assertFalse(result.timings().containsKey("unexpected_text_key"),
                "timings must be restricted to a numeric whitelist");
    }

    @Test
    void parseResponseNeverRetainsPromptOrResultText() throws Exception {
        String body = """
                {"choices":[{"message":{"content":"{\\"sql\\":\\"SELECT * FROM t\\"}"}}],
                "timings":{"prompt_n":5,"predicted_n":3}}
                """;

        LlamaCppPrefixChatClient.ChatResult result =
                new LlamaCppPrefixChatClient().parseResponse(body, false);

        assertFalse(result.timings().keySet().stream()
                .anyMatch(key -> key.toLowerCase().contains("content")
                        || key.toLowerCase().contains("prompt_text")
                        || key.toLowerCase().contains("result")),
                "timings must never carry prompt or result text");
        assertFalse(result.timings().values().stream()
                .anyMatch(value -> value instanceof String s && s.contains("SELECT")),
                "timings must never carry SQL or prompt text");
    }

    @Test
    void safetyCappedOptionsBoundTheDecodeWithoutTouchingThinking() {
        LlamaCppPrefixChatClient.ChatOptions capped =
                LlamaCppPrefixChatClient.ChatOptions.safetyCap(1024);

        assertEquals(1024, capped.maxTokens());
        assertFalse(capped.enableThinking());
    }
}
