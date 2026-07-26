package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
