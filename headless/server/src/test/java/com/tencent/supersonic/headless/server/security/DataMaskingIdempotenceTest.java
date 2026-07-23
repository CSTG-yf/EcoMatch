package com.tencent.supersonic.headless.server.security;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMaskingIdempotenceTest {

    @Test
    void repeatedMaskingKeepsValuesAndMetadataStable() {
        DataMaskingService service =
                new DataMaskingService("", "", "customer_name=FIRST_LAST,account_no=LAST4");
        SemanticQueryResp response = response();
        SemanticSchemaResp schema = schema();
        User analyst = User.get(2L, "analyst");

        service.mask(response, schema, analyst);
        Map<String, Object> firstMaskedRow = new LinkedHashMap<>(response.getResultList().get(0));
        service.mask(response, schema, analyst);

        assertEquals(firstMaskedRow, response.getResultList().get(0));
        assertEquals("张***三", response.getResultList().get(0).get("customer_name"));
        assertEquals("****1234", response.getResultList().get(0).get("account_no"));
        assertEquals(new LinkedHashSet<>(List.of("customer_name", "account_no", "legacy_mask")),
                response.getMaskedColumns());
        assertTrue(response.isDataMasked());
    }

    private SemanticQueryResp response() {
        SemanticQueryResp response = new SemanticQueryResp();
        response.setColumns(List.of(new QueryColumn("customer_name", "VARCHAR", "customer_name"),
                new QueryColumn("account_no", "VARCHAR", "account_no")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customer_name", "张小三");
        row.put("account_no", "622200001234");
        response.setResultList(List.of(row));
        response.setDataMasked(true);
        response.setMaskedColumns(new LinkedHashSet<>(List.of("legacy_mask")));
        return response;
    }

    private SemanticSchemaResp schema() {
        DimSchemaResp customerName = sensitiveDimension("customer_name");
        DimSchemaResp accountNo = sensitiveDimension("account_no");
        SemanticSchemaResp schema = new SemanticSchemaResp();
        schema.setDimensions(List.of(customerName, accountNo));
        return schema;
    }

    private DimSchemaResp sensitiveDimension(String field) {
        DimSchemaResp dimension = new DimSchemaResp();
        dimension.setName(field);
        dimension.setBizName(field);
        dimension.setSensitiveLevel(SensitiveLevelEnum.HIGH.getCode());
        return dimension;
    }
}
