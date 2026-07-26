package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BankPlanCandidateRankerTest {

    @Test
    void shouldMergeEquivalentCandidatesAndPreferCompleteSemanticEvidence() {
        BankPlanCandidateRanker ranker = new BankPlanCandidateRanker();

        BankPlanCandidateRanker.Selection selection =
                ranker.select(List.of(BankPlanCandidateRanker.Candidate.valid("equivalent", 72D),
                        BankPlanCandidateRanker.Candidate.valid("equivalent", 84D),
                        BankPlanCandidateRanker.Candidate.valid("complete", 96D),
                        BankPlanCandidateRanker.Candidate.rejected("missing-condition",
                                "REQUIRED_FILTER_MISSING")));

        assertEquals("complete", selection.getSelected().getFingerprint());
        assertEquals(2, selection.getUniqueCandidateCount());
        assertEquals(1, selection.getRejectedCandidateCount());
        assertFalse(selection.getRejectionReasons().contains("complete"));
    }

    @Test
    void shouldUseFingerprintAsAStableTieBreak() {
        BankPlanCandidateRanker ranker = new BankPlanCandidateRanker();

        BankPlanCandidateRanker.Selection selection =
                ranker.select(List.of(BankPlanCandidateRanker.Candidate.valid("b-fingerprint", 90D),
                        BankPlanCandidateRanker.Candidate.valid("a-fingerprint", 90D)));

        assertEquals("a-fingerprint", selection.getSelected().getFingerprint());
    }
}
