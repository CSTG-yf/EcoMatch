package com.tencent.supersonic.chat.server.util;

import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.headless.api.pojo.request.BankPlanRepairContext;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class QueryReqConverterTest {

    @Test
    void preservesInternalBankPlanRepairContextForTheHeadlessParser() {
        BankPlanRepairContext repairContext =
                BankPlanRepairContext.of("{\"status\":\"FAILED\"}", "{\"version\":\"1.0\"}");
        ChatParseReq request = ChatParseReq.builder().queryId(20L).queryText("question")
                .bankPlanRepairContext(repairContext).build();
        ParseContext parseContext = new ParseContext(request, new ChatParseResp(20L));
        Agent agent = new Agent();
        agent.setToolConfig("{\"tools\":[]}");
        parseContext.setAgent(agent);

        QueryNLReq queryRequest = QueryReqConverter.buildQueryNLReq(parseContext);

        assertSame(repairContext, queryRequest.getBankPlanRepairContext());
    }
}
