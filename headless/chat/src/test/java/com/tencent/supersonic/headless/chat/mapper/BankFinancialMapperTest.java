package com.tencent.supersonic.headless.chat.mapper;

import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SchemaValueMap;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFinancialMapperTest {

    @Test
    void shouldMapBankMetricAndOrganizationAlias() {
        QueryNLReq request = new QueryNLReq();
        request.setQueryText("A行2026年一季度末不良率是多少");
        ChatQueryContext context = new ChatQueryContext(request);
        context.setSemanticSchema(schema());

        new BankFinancialMapper().doMap(context);

        List<SchemaElementMatch> matches = context.getMapInfo().getMatchedElements(1L);
        assertTrue(matches.stream().anyMatch(item -> item.getElement().getId().equals(101L)
                && item.getElement().getType() == SchemaElementType.METRIC));
        assertTrue(matches.stream().anyMatch(
                item -> item.getElement().getId().equals(201L) && "ORG001".equals(item.getWord())));
        assertTrue(context.getRequest().getQueryText().contains("江苏省A市农商行"));
        assertEquals("ZB013", context.getBankIntentResult().getMetrics().get(0).getCode());
    }

    private SemanticSchema schema() {
        DataSetSchema schema = new DataSetSchema();
        schema.setDataSet(SchemaElement.builder().id(1L).dataSetId(1L).name("银行指标")
                .type(SchemaElementType.DATASET).build());
        schema.setMetrics(Set.of(SchemaElement.builder().id(101L).dataSetId(1L).name("不良贷款率")
                .bizName("zb013").type(SchemaElementType.METRIC).build()));
        SchemaValueMap organization = new SchemaValueMap();
        organization.setTechName("ORG001");
        organization.setBizName("江苏省A市农商行");
        organization.setAlias(List.of("A行"));
        schema.setDimensionValues(Set.of(SchemaElement.builder().id(201L).dataSetId(1L).name("机构")
                .bizName("bank_organization").type(SchemaElementType.VALUE)
                .schemaValueMaps(List.of(organization)).build()));
        return new SemanticSchema(new ArrayList<>(List.of(schema)));
    }
}
