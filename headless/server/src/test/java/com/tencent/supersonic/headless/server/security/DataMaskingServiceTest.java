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
import java.util.Set;

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
        DataMaskingService service = new DataMaskingService("auditor", "data_steward");
        SemanticSchemaResp schema = schema("mobile");
        SemanticQueryResp auditorResponse = response("mobile", "13812345678");
        SemanticQueryResp adminResponse = response("mobile", "13812345678");
        SemanticQueryResp stewardResponse = response("mobile", "13812345678");
        User steward = User.get(3L, "steward");
        steward.setRoles(Set.of("data_steward"));

        service.mask(auditorResponse, schema, User.get(2L, "auditor"));
        service.mask(adminResponse, schema, User.getDefaultUser());
        service.mask(stewardResponse, schema, steward);

        assertEquals("13812345678", auditorResponse.getResultList().get(0).get("mobile"));
        assertEquals("13812345678", adminResponse.getResultList().get(0).get("mobile"));
        assertEquals("13812345678", stewardResponse.getResultList().get(0).get("mobile"));
        assertFalse(auditorResponse.isDataMasked());
    }

    @Test
    void handlesNullRolesWithoutSkippingMasking() {
        DataMaskingService service = new DataMaskingService("", "data_steward");
        SemanticQueryResp response = response("mobile", "13812345678");
        User analyst = User.get(2L, "analyst");
        analyst.setRoles(null);

        service.mask(response, schema("mobile"), analyst);

        assertEquals("138****5678", response.getResultList().get(0).get("mobile"));
    }

    @Test
    void appliesConfiguredFieldStrategies() {
        DataMaskingService service =
                new DataMaskingService("", "", "customer_name=FULL,account_no=LAST4");

        assertEquals("****", service.maskValue("customer_name", "张三"));
        assertEquals("****1234", service.maskValue("account_no", "622200001234"));
    }

    @Test
    void rejectsInvalidFieldStrategyConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DataMaskingService("", "", "account_no=UNKNOWN"));
    }

    @Test
    void masksAllSupportedSensitiveValueTypes() {
        DataMaskingService service = new DataMaskingService("", "");

        assertEquals("a****e@bank.cn", service.maskValue("email", "alice@bank.cn"));
        assertEquals("138****5678", service.maskValue("mobile", "13812345678"));
        assertEquals("320101****1234", service.maskValue("id_card", "320101199001011234"));
        assertEquals("6222****1234", service.maskValue("account_no", "622200001234"));
        assertEquals("张***", service.maskValue("customer_name", "张三"));
        assertEquals("****", service.maskValue("balance", 1000));
    }

    @Test
    void toleratesMissingColumnsAndSchemaCollections() {
        DataMaskingService service = new DataMaskingService("", "");
        SemanticQueryResp response = response("mobile", "13812345678");
        response.setColumns(null);
        SemanticSchemaResp schema = new SemanticSchemaResp();
        schema.setDimensions(null);
        schema.setMetrics(null);

        service.mask(response, schema, User.get(2L, "analyst"));

        assertEquals("13812345678", response.getResultList().get(0).get("mobile"));
        assertFalse(response.isDataMasked());
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
