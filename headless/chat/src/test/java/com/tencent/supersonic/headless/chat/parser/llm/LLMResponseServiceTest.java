package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
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
        ParseResp.BankRoutingAttemptTelemetry created = new ParseResp.BankRoutingAttemptTelemetry(
                true, true, ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, true,
                ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED, SqlErrorType.JOIN_ERROR);

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
        ParseResp.BankRoutingAttemptTelemetry created = new ParseResp.BankRoutingAttemptTelemetry(
                true, true, ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, true,
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
        assertNull(LLMSqlParser
                .bankCandidateCompilerReason(new IllegalStateException("opaque-details")));
    }

    @Test
    void shouldReportTypedValidationRejectionWithoutChangingLegacyDeduplicationApi() {
        LLMResp response = new LLMResp();
        response.setSqlRespMap(
                Map.of("SELECT metric_value FROM semantic_dataset a JOIN other_dataset b",
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
    void shouldAcceptOnlyCompilerOwnedCompleteBankChangeEvidenceForTopNPresentation() {
        String sql = """
                WITH current_snapshot AS (
                  SELECT bank_organization, SUM(zb001) AS current_value
                  FROM bank_indicator_dataset WHERE bank_data_date = '2026-03-31'
                  GROUP BY bank_organization
                ), baseline_snapshot AS (
                  SELECT bank_organization, SUM(zb001) AS baseline_value
                  FROM bank_indicator_dataset WHERE bank_data_date = '2024-12-31'
                  GROUP BY bank_organization
                )
                SELECT current_snapshot.bank_organization, current_value, baseline_value
                FROM current_snapshot JOIN baseline_snapshot
                  ON current_snapshot.bank_organization = baseline_snapshot.bank_organization
                ORDER BY current_snapshot.bank_organization ASC
                """;
        BankQueryPlan plan = new BankQueryPlan();
        plan.setIntent(BankIntentType.CHANGE);
        LLMResp response = new LLMResp();
        response.setBankQueryPlan(plan);
        response.setSqlOutput(sql);
        response.setSqlRespMap(Map.of(sql, LLMSqlResp.builder().sqlWeight(1D).build()));

        LLMReq request = bankConstrainedRequest();
        LLMResponseService.DeduplicationOutcome outcome =
                new LLMResponseService().getDeduplicationSqlRespWithOutcome(1, response, request);

        assertEquals(Map.of(sql, response.getSqlRespMap().get(sql)), outcome.acceptedCandidates());
        assertTrue(!outcome.allCandidatesRejectedByValidation());
    }

    @Test
    void shouldKeepTopNValidationForNonChangeBankPlans() {
        String sql = "SELECT bank_organization, zb001 FROM bank_indicator_dataset "
                + "WHERE bank_data_date = '2026-03-31' ORDER BY zb001 DESC";
        BankQueryPlan plan = new BankQueryPlan();
        plan.setIntent(BankIntentType.RANKING);
        LLMResp response = new LLMResp();
        response.setBankQueryPlan(plan);
        response.setSqlOutput(sql);
        response.setSqlRespMap(Map.of(sql, LLMSqlResp.builder().sqlWeight(1D).build()));

        LLMResponseService.DeduplicationOutcome outcome = new LLMResponseService()
                .getDeduplicationSqlRespWithOutcome(1, response, bankConstrainedRequest());

        assertTrue(outcome.acceptedCandidates().isEmpty());
        assertEquals(SqlErrorType.DEFINITION_ERROR, outcome.validationErrorType());
    }

    private LLMReq bankConstrainedRequest() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetName("bank_indicator_dataset");
        schema.setDimensions(
                java.util.List.of(SchemaElement.builder().bizName("bank_organization").build(),
                        SchemaElement.builder().bizName("bank_data_date").build()));
        schema.setMetrics(java.util.List.of(SchemaElement.builder().bizName("zb001").build()));
        LLMReq request = new LLMReq();
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSchema(schema);
        request.setQueryText("全省各项存款余额增幅排名前三");
        return request;
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
