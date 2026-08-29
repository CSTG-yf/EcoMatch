package com.tencent.supersonic.headless.server.facade.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.api.pojo.request.QueryDataSetReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlsReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticQueryRequestSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void externalSqlJsonCannotDisableAuthorization() throws Exception {
        QuerySqlReq request = objectMapper.readValue("{\"sql\":\"select 1\",\"needAuth\":false}",
                QuerySqlReq.class);

        assertTrue(request.isNeedAuth());
    }

    @Test
    void externalSqlJsonCannotEnableSemanticCompilerExecutionMode() throws Exception {
        QuerySqlReq request = objectMapper.readValue(
                "{\"sql\":\"select 1\",\"trustedCompiledSql\":true}", QuerySqlReq.class);

        assertFalse(request.isTrustedCompiledSql());
    }

    @Test
    void externalSqlJsonCannotSupplyPhysicalSqlInfo() throws Exception {
        QuerySqlReq request = objectMapper.readValue("{\"sql\":\"select 1\",\"sqlInfo\":{"
                + "\"querySQL\":\"select secret from physical_table\","
                + "\"correctedQuerySQL\":\"select repaired from physical_table\"}}",
                QuerySqlReq.class);

        assertTrue(request.getSqlInfo() != null);
        assertTrue(request.getSqlInfo().getQuerySQL() == null);
        assertTrue(request.getSqlInfo().getCorrectedQuerySQL() == null);
    }

    @Test
    void externalSqlJsonCannotClaimRowPermissionWasApplied() throws Exception {
        QuerySqlReq request = objectMapper.readValue(
                "{\"sql\":\"select 1\",\"rowPermissionApplied\":true}", QuerySqlReq.class);

        assertFalse(request.isRowPermissionApplied());
        request.setRowPermissionApplied(true);
        assertTrue(request.isRowPermissionApplied());
    }

    @Test
    void externalDataSetJsonCannotDisableAuthorization() throws Exception {
        QueryDataSetReq request = objectMapper.readValue("{\"dataSetId\":1,\"needAuth\":false}",
                QueryDataSetReq.class);

        assertTrue(request.isNeedAuth());
    }

    @Test
    void externalStructuredJsonCannotDisableAuthorization() throws Exception {
        QueryStructReq request =
                objectMapper.readValue("{\"groups\":[],\"needAuth\":false}", QueryStructReq.class);

        assertTrue(request.isNeedAuth());
    }

    @Test
    void externalBatchSqlJsonCannotDisableAuthorization() throws Exception {
        QuerySqlsReq request = objectMapper
                .readValue("{\"sqls\":[\"select 1\"],\"needAuth\":false}", QuerySqlsReq.class);

        assertTrue(request.isNeedAuth());
    }

    @Test
    void trustedServerCodeCanStillDisableAuthorization() {
        QuerySqlReq request = new QuerySqlReq();

        request.setNeedAuth(false);

        assertFalse(request.isNeedAuth());
    }

    @Test
    void externalJsonCannotEnableNativeLayerMode() throws Exception {
        QuerySqlReq sqlRequest = objectMapper
                .readValue("{\"sql\":\"select 1\",\"innerLayerNative\":true}", QuerySqlReq.class);
        QueryDataSetReq dataSetRequest = objectMapper
                .readValue("{\"dataSetId\":1,\"innerLayerNative\":true}", QueryDataSetReq.class);
        QueryStructReq structRequest = objectMapper
                .readValue("{\"groups\":[],\"innerLayerNative\":true}", QueryStructReq.class);
        QueryMetricReq metricRequest = objectMapper
                .readValue("{\"metricIds\":[1],\"innerLayerNative\":true}", QueryMetricReq.class);

        assertFalse(sqlRequest.isInnerLayerNative());
        assertFalse(dataSetRequest.isInnerLayerNative());
        assertFalse(structRequest.isInnerLayerNative());
        assertFalse(metricRequest.isInnerLayerNative());
    }

    @Test
    void trustedServerCodeCanEnableNativeLayerMode() {
        QuerySqlReq request = new QuerySqlReq();

        request.setInnerLayerNative(true);

        assertTrue(request.isInnerLayerNative());
    }
}
