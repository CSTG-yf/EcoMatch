package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
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
}
