package com.tencent.supersonic.headless.chat.parser.llm.bank;

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
}
