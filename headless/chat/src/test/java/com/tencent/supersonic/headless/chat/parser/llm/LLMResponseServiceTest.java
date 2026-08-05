package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlResp;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMResponseServiceTest {

    @Test
    void shouldExposeOnlyTypedBankCandidateRejectionFacts() {
        ParseResp.BankRoutingAttemptTelemetry validationRejected =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, false,
                        ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                        SqlErrorType.JOIN_ERROR);
        ParseResp.BankRoutingAttemptTelemetry noResponse =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, false,
                        ParseResp.BankCandidateRejectionState.NO_RESPONSE, null);
        ParseResp.BankRoutingAttemptTelemetry noCandidate =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, false,
                        ParseResp.BankCandidateRejectionState.NO_CANDIDATE, null);
        ParseResp.BankRoutingAttemptTelemetry created =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, true,
                        ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                        SqlErrorType.JOIN_ERROR);

        assertEquals(ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                validationRejected.getCandidateRejectionState());
        assertEquals(SqlErrorType.JOIN_ERROR, validationRejected.getCandidateValidationErrorType());
        assertEquals(ParseResp.BankCandidateRejectionState.NO_RESPONSE,
                noResponse.getCandidateRejectionState());
        assertNull(noResponse.getCandidateValidationErrorType());
        assertEquals(ParseResp.BankCandidateRejectionState.NO_CANDIDATE,
                noCandidate.getCandidateRejectionState());
        assertTrue(created.isLlmCandidateCreated());
        assertNull(created.getCandidateRejectionState());
        assertNull(created.getCandidateValidationErrorType());
    }

    @Test
    void shouldExposeOnlyTypedBankCandidateCompilerReasons() {
        ParseResp.BankRoutingAttemptTelemetry compilerRejected =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, false,
                        ParseResp.BankCandidateRejectionState.COMPILER_EXCEPTION, null,
                        ParseResp.BankCandidateCompilerReason.S2SQL_RENDER_FAILED);
        ParseResp.BankRoutingAttemptTelemetry validationRejected =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, false,
                        ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                        SqlErrorType.JOIN_ERROR,
                        ParseResp.BankCandidateCompilerReason.S2SQL_RENDER_FAILED);
        ParseResp.BankRoutingAttemptTelemetry created =
                new ParseResp.BankRoutingAttemptTelemetry(true, true,
                        ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, true,
                        ParseResp.BankCandidateRejectionState.COMPILER_EXCEPTION, null,
                        ParseResp.BankCandidateCompilerReason.S2SQL_RENDER_FAILED);

        assertEquals(ParseResp.BankCandidateCompilerReason.S2SQL_RENDER_FAILED,
                compilerRejected.getCandidateCompilerReason());
        assertNull(validationRejected.getCandidateCompilerReason());
        assertNull(created.getCandidateCompilerReason());
        assertEquals(ParseResp.BankCandidateCompilerReason.DIMENSION_UNAVAILABLE,
                LLMSqlParser.bankCandidateCompilerReason(new IllegalStateException("opaque-details",
                        new BankPlanCompilationException(
                                BankPlanCompilationException.Reason.DIMENSION_UNAVAILABLE,
                                "opaque-details"))));
        assertEquals(ParseResp.BankCandidateRejectionState.COMPILER_EXCEPTION,
                LLMSqlParser.bankCandidateRejectionState(new IllegalStateException("opaque-details",
                        new BankPlanCompilationException(
                                BankPlanCompilationException.Reason.DIMENSION_UNAVAILABLE,
                                "opaque-details"))));
        assertNull(LLMSqlParser.bankCandidateCompilerReason(
                new IllegalStateException("opaque-details")));
    }

    @Test
    void shouldReportTypedValidationRejectionWithoutChangingLegacyDeduplicationApi() {
        LLMResp response = new LLMResp();
        response.setSqlRespMap(Map.of(
                "SELECT metric_value FROM semantic_dataset a JOIN other_dataset b",
                LLMSqlResp.builder().sqlWeight(1D).build()));

        LLMResponseService service = new LLMResponseService();
        LLMResponseService.DeduplicationOutcome outcome =
                service.getDeduplicationSqlRespWithOutcome(1, response, null);

        assertTrue(outcome.acceptedCandidates().isEmpty());
        assertTrue(outcome.allCandidatesRejectedByValidation());
        assertEquals(SqlErrorType.JOIN_ERROR, outcome.validationErrorType());
        assertTrue(service.getDeduplicationSqlResp(1, response, null).isEmpty());
    }

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
