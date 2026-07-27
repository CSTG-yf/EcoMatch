package com.tencent.supersonic.headless.server.facade.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.api.pojo.request.QueryDataSetReq;
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
}
