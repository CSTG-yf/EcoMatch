package com.tencent.supersonic.headless.server.security;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMaskingServiceTest {

    @Test
    void masksSensitiveValuesForRegularUser() {
        DataMaskingService service = new DataMaskingService("", "");
        SemanticQueryResp response = response("mobile", "13812345678");
        SemanticSchemaResp schema = schema("mobile");

        service.mask(response, schema, User.get(2L, "analyst"));

        assertEquals("138****5678", response.getResultList().get(0).get("mobile"));
        assertTrue(response.isDataMasked());
        assertTrue(response.getMaskedColumns().contains("mobile"));
    }

    @Test
    void keepsRawValuesForConfiguredAndAdminUsers() {
        DataMaskingService service = new DataMaskingService("auditor", "");
        SemanticSchemaResp schema = schema("mobile");
        SemanticQueryResp auditorResponse = response("mobile", "13812345678");
        SemanticQueryResp adminResponse = response("mobile", "13812345678");

        service.mask(auditorResponse, schema, User.get(2L, "auditor"));
        service.mask(adminResponse, schema, User.getDefaultUser());

        assertEquals("13812345678", auditorResponse.getResultList().get(0).get("mobile"));
        assertEquals("13812345678", adminResponse.getResultList().get(0).get("mobile"));
        assertFalse(auditorResponse.isDataMasked());
    }

    private SemanticQueryResp response(String field, Object value) {
        SemanticQueryResp response = new SemanticQueryResp();
        response.setColumns(List.of(new QueryColumn(field, "VARCHAR", field)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(field, value);
        response.setResultList(List.of(row));
        return response;
    }

    private SemanticSchemaResp schema(String field) {
        DimSchemaResp dimension = new DimSchemaResp();
        dimension.setName(field);
        dimension.setBizName(field);
        dimension.setSensitiveLevel(SensitiveLevelEnum.HIGH.getCode());
        SemanticSchemaResp schema = new SemanticSchemaResp();
        schema.setDimensions(List.of(dimension));
        return schema;
    }
}
