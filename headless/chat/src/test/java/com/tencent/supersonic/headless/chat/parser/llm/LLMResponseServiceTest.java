package com.tencent.supersonic.headless.chat.parser.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LLMResponseServiceTest {

    @Test
    void shouldUseBankSemanticEvidenceInsteadOfQuestionLength() {
        double score = LLMResponseService.parseScore("一个明显更长但不应改变候选排序的银行问题", 1D,
                Map.of("bank.nl2sql.semanticScore", 91D));

        assertEquals(91D, score);
    }

    @Test
    void shouldRetainLegacyScoreForNonBankCandidates() {
        double score = LLMResponseService.parseScore("abcd", 0.5D, Map.of());

        assertEquals(6D, score);
    }
}
