package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryStructReqOrderingTest {

    @Test
    void shouldPreserveDeclaredGroupOrderWhenBuildingSelectItems() throws Exception {
        QueryStructReq request = new QueryStructReq();
        request.setGroups(List.of("z", "a"));
        request.setAggregators(List.of(new Aggregator("metric", AggOperatorEnum.SUM)));

        Method buildSelectItems =
                QueryStructReq.class.getDeclaredMethod("buildSelectItems", QueryStructReq.class);
        buildSelectItems.setAccessible(true);
        List<?> selectItems = (List<?>) buildSelectItems.invoke(request, request);

        assertEquals(List.of("z", "a", "SUM(metric)"),
                selectItems.stream().map(Object::toString).toList());
    }
}
