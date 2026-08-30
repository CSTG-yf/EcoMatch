package com.tencent.supersonic.headless.server.service.bank;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFallbackSqlProbe;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the server-side free-SQL publish-gate probe: translation failures classify as
 * TRANSLATE_FAILED, execution errors as EXECUTION_FAILED with the failure layer appended, success
 * reports the physical result columns and row count, and non-SELECT/WITH input is declined before
 * any service call.
 */
class BankFallbackSqlProbeImplTest {

    private static final String GOOD_S2SQL =
            "SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                    + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' "
                    + "GROUP BY bank_organization";

    private SemanticLayerService semanticLayerService;
    private BankFallbackSqlProbeImpl probe;

    @BeforeEach
    void setUp() {
        semanticLayerService = mock(SemanticLayerService.class);
        probe = new BankFallbackSqlProbeImpl(semanticLayerService);
    }

    @Test
    void translateFailureIsReportedAsTranslateFailedWithoutExecuting() throws Exception {
        when(semanticLayerService.translate(any(SemanticQueryReq.class), any(User.class)))
                .thenThrow(new RuntimeException("parse exception: unknown column 拨备率"));

        BankFallbackSqlProbe.ProbeReport report = probe.probe(bankRequest(), GOOD_S2SQL);

        assertFalse(report.ok());
        assertEquals(BankFallbackSqlProbe.ERROR_TRANSLATE_FAILED, report.errorCode());
        assertTrue(report.message().contains("parse exception"));
        assertTrue(report.message().contains("拨备率"));
        verify(semanticLayerService, never()).queryByReq(any(), any());
    }

    @Test
    void translateNotOkIsReportedAsTranslateFailed() throws Exception {
        when(semanticLayerService.translate(any(SemanticQueryReq.class), any(User.class)))
                .thenReturn(SemanticTranslateResp.builder().isOk(false).errMsg("bad ontology sql")
                        .build());

        BankFallbackSqlProbe.ProbeReport report = probe.probe(bankRequest(), GOOD_S2SQL);

        assertFalse(report.ok());
        assertEquals(BankFallbackSqlProbe.ERROR_TRANSLATE_FAILED, report.errorCode());
        assertEquals("bad ontology sql", report.message());
    }

    @Test
    void executionErrorIsReportedAsExecutionFailedWithRowCapWrapping() throws Exception {
        String physicalSql = "SELECT bank_organization FROM t1__bank_dataset LIMIT 1000";
        when(semanticLayerService.translate(any(SemanticQueryReq.class), any(User.class)))
                .thenReturn(SemanticTranslateResp.builder().isOk(true).querySQL(physicalSql)
                        .build());
        SemanticQueryResp failed = new SemanticQueryResp();
        failed.setErrorMsg("Query execution failed");
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("failureLayer", "JDBC_GRAMMAR");
        failed.setExecutionTelemetry(telemetry);
        when(semanticLayerService.queryByReq(any(SemanticQueryReq.class), any(User.class)))
                .thenReturn(failed);

        BankFallbackSqlProbe.ProbeReport report = probe.probe(bankRequest(), GOOD_S2SQL);

        assertFalse(report.ok());
        assertEquals(BankFallbackSqlProbe.ERROR_EXECUTION_FAILED, report.errorCode());
        assertTrue(report.message().contains("Query execution failed"));
        assertTrue(report.message().contains("failureLayer=JDBC_GRAMMAR"));

        ArgumentCaptor<SemanticQueryReq> requests =
                ArgumentCaptor.forClass(SemanticQueryReq.class);
        verify(semanticLayerService).queryByReq(requests.capture(), any(User.class));
        QuerySqlReq executed = (QuerySqlReq) requests.getValue();
        assertEquals("SELECT * FROM (" + physicalSql + ") BANK_FALLBACK_PROBE LIMIT 5",
                executed.getSqlInfo().getCorrectedQuerySQL());
        assertFalse(executed.isNeedAuth());
        assertFalse(executed.isTrustedCompiledSql());
        assertEquals(12L, executed.getDataSetId().longValue());
    }

    @Test
    void successReportsPhysicalColumnsAndRowCount() throws Exception {
        when(semanticLayerService.translate(any(SemanticQueryReq.class), any(User.class)))
                .thenReturn(SemanticTranslateResp.builder().isOk(true)
                        .querySQL("SELECT bank_organization FROM t1__bank_dataset").build());
        SemanticQueryResp success = new SemanticQueryResp();
        success.setColumns(List.of(new QueryColumn("org_code", "CHAR", "org_code"),
                new QueryColumn("metric_value", "DOUBLE", "metric_value")));
        success.setResultList(List.of(Map.of("org_code", "ORG001", "metric_value", 1.0D),
                Map.of("org_code", "ORG002", "metric_value", 2.0D)));
        when(semanticLayerService.queryByReq(any(SemanticQueryReq.class), any(User.class)))
                .thenReturn(success);

        BankFallbackSqlProbe.ProbeReport report = probe.probe(bankRequest(), GOOD_S2SQL);

        assertNotNull(report);
        assertTrue(report.ok());
        assertEquals(List.of("org_code", "metric_value"), report.resultColumns());
        assertEquals(2, report.resultRowCount());
    }

    @Test
    void nonSelectShapeOrMissingDataSetIdDeclinesBeforeAnyServiceCall() {
        BankFallbackSqlProbe.ProbeReport nonSelect = probe.probe(bankRequest(),
                "DELETE FROM bank_dataset");
        assertFalse(nonSelect.ok());
        assertEquals(BankFallbackSqlProbe.ERROR_OTHER, nonSelect.errorCode());
        assertTrue(nonSelect.message().contains("SELECT/WITH"));

        BankFallbackSqlProbe.ProbeReport multiStatement = probe.probe(bankRequest(),
                "SELECT 1; DROP TABLE bank_dataset");
        assertFalse(multiStatement.ok());
        assertEquals(BankFallbackSqlProbe.ERROR_OTHER, multiStatement.errorCode());

        LLMReq noDataSet = new LLMReq();
        noDataSet.setSchema(new LLMReq.LLMSchema());
        BankFallbackSqlProbe.ProbeReport missingId = probe.probe(noDataSet, GOOD_S2SQL);
        assertFalse(missingId.ok());
        assertEquals(BankFallbackSqlProbe.ERROR_OTHER, missingId.errorCode());

        verifyNoInteractions(semanticLayerService);
    }

    @Test
    void translateExceptionsStayUnderTheMessageBudget() throws Exception {
        when(semanticLayerService.translate(any(SemanticQueryReq.class), any(User.class)))
                .thenThrow(new RuntimeException("x".repeat(500)));

        BankFallbackSqlProbe.ProbeReport report = probe.probe(bankRequest(), GOOD_S2SQL);

        assertFalse(report.ok());
        assertEquals(300, report.message().length());
    }

    @Test
    void wrapWithRowCapKeepsWithStatementsParseableAndStripsSeparators() {
        String withNoLimit = "WITH x AS (SELECT 1 AS a) SELECT a FROM x";
        assertEquals(withNoLimit + " LIMIT 5", BankFallbackSqlProbeImpl.wrapWithRowCap(withNoLimit));

        String withLimit = "WITH x AS (SELECT 1 AS a) SELECT a FROM x LIMIT 10";
        assertEquals(withLimit, BankFallbackSqlProbeImpl.wrapWithRowCap(withLimit));

        String plainSelect = "SELECT a FROM t";
        assertEquals("SELECT * FROM (SELECT a FROM t) BANK_FALLBACK_PROBE LIMIT 5",
                BankFallbackSqlProbeImpl.wrapWithRowCap(plainSelect + " ; "));
    }

    private LLMReq bankRequest() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("bank_dataset");
        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText("2026年3月末各机构的存款规模是多少");
        llmReq.setSchema(schema);
        return llmReq;
    }
}
