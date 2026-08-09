package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.request.BankPlanRepairContext;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMRequestServiceTest {

    @Test
    void shouldRetainBankOrganizationDimensionWhenFieldTrimmingDropsIt() {
        SchemaElement matchedDate = SchemaElement.builder().dataSetId(33L).bizName("bank_data_date")
                .name("数据日期").build();
        SchemaElement bankOrganization = SchemaElement.builder().dataSetId(33L)
                .bizName("bank_organization").name("机构").build();
        SchemaElement otherDataSetOrganization = SchemaElement.builder().dataSetId(34L)
                .bizName("bank_organization").name("其他机构").build();

        List<SchemaElement> dimensions =
                LLMRequestService.ensureBankOrganizationDimension(List.of(matchedDate),
                        List.of(matchedDate, bankOrganization, otherDataSetOrganization), 33L);

        assertEquals(List.of("bank_data_date", "bank_organization"),
                dimensions.stream().map(SchemaElement::getBizName).toList());
    }

    @Test
    void shouldRouteOnlyDetectedBankDatasetsToConstrainedPlanWhenEnabled() {
        SchemaElement bankDate = SchemaElement.builder().dataSetId(33L).bizName("bank_data_date")
                .name("data_date").build();
        SchemaElement bankOrganization = SchemaElement.builder().dataSetId(33L)
                .bizName("bank_organization").name("bank_organization").build();
        SchemaElement unrelatedOrganization = SchemaElement.builder().dataSetId(34L)
                .bizName("bank_organization").name("bank_organization").build();

        assertEquals(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN,
                LLMRequestService.selectSqlGenType(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                        List.of(bankDate, bankOrganization, unrelatedOrganization), 33L, true));
        assertEquals(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                LLMRequestService.selectSqlGenType(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                        List.of(bankDate, bankOrganization), 33L, false));
        assertEquals(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                LLMRequestService.selectSqlGenType(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                        List.of(unrelatedOrganization), 34L, true));
    }

    @Test
    void shouldExposeSafeRoutingTelemetryWhenEnabledBankRoutingFallsBackToGeneric() {
        SchemaElement nonBankDimension = SchemaElement.builder().dataSetId(33L)
                .bizName("ordinary_dimension").name("普通维度").build();

        LLMRequestService.BankRoutingDecision decision =
                LLMRequestService.selectBankRouting(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                        List.of(nonBankDimension), 33L, true);

        assertEquals(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY,
                decision.selectedSqlGenType());
        assertEquals(Map.of("bankConstrainedPlanEnabled", true, "bankDatasetQualified", false,
                "selectedSqlGenType", "ONE_PASS_SELF_CONSISTENCY"), decision.telemetry());
    }

    @Test
    void shouldExposeRoutingAttemptBeforeLlmCandidateCreation() {
        ParseResp parseResp = new ParseResp("safe");

        LLMSqlParser.publishBankRoutingAttemptTelemetry(parseResp, routingRequest(), false);

        ParseResp.BankRoutingAttemptTelemetry telemetry =
                parseResp.getBankRoutingAttemptTelemetry();
        assertTrue(telemetry.isBankConstrainedPlanEnabled());
        assertFalse(telemetry.isBankDatasetQualified());
        assertEquals(ParseResp.BankRoutingSqlGenType.ONE_PASS_SELF_CONSISTENCY,
                telemetry.getSelectedSqlGenType());
        assertFalse(telemetry.isLlmCandidateCreated());
    }

    @Test
    void shouldMarkRoutingAttemptWhenLlmCandidateIsCreated() {
        ParseResp parseResp = new ParseResp("safe");

        LLMSqlParser.publishBankRoutingAttemptTelemetry(parseResp, routingRequest(), true);

        assertTrue(parseResp.getBankRoutingAttemptTelemetry().isLlmCandidateCreated());
    }

    @Test
    void shouldTransferInternalBankPlanRepairContextToLlmRequest() {
        BankPlanToolResult failure = BankPlanToolResult.failed(1, "trace-1", "fingerprint-1",
                BankPlanToolResult.Stage.DATABASE_EXECUTE, "JDBC_GRAMMAR", Map.of(),
                List.of("regenerate the complete plan"));
        QueryNLReq queryRequest = new QueryNLReq();
        queryRequest.setBankPlanRepairContext(BankPlanRepairContext.of(failure.toRepairFeedback(),
                "{\"version\":\"1.0\",\"intent\":\"DETAIL\"}"));
        LLMReq llmRequest = new LLMReq();

        LLMRequestService.applyBankPlanRepairContext(queryRequest, llmRequest);

        assertEquals(BankPlanToolResult.Stage.DATABASE_EXECUTE,
                llmRequest.getBankPlanToolResult().getFailedStage());
        assertEquals("JDBC_GRAMMAR", llmRequest.getBankPlanToolResult().getErrorCode());
        assertEquals("trace-1", llmRequest.getBankPlanToolResult().getTraceId());
        assertEquals("{\"version\":\"1.0\",\"intent\":\"DETAIL\"}",
                llmRequest.getPreviousBankQueryPlanJson());
    }

    private static LLMReq routingRequest() {
        LLMReq request = new LLMReq();
        request.setSqlGenType(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY);
        request.setBankRoutingTelemetry(Map.of(
                "bankConstrainedPlanEnabled", true,
                "bankDatasetQualified", false,
                "selectedSqlGenType", "ONE_PASS_SELF_CONSISTENCY",
                "raw", "opaque-details"));
        return request;
    }
}
