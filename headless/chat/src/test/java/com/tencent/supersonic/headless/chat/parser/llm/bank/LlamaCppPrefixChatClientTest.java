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
}
