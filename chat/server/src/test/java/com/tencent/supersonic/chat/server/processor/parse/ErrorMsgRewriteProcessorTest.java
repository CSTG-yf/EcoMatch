package com.tencent.supersonic.chat.server.processor.parse;

import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorMsgRewriteProcessorTest {

    @Test
    void rejectsTerminalInfrastructureErrorsBeforeCallingRewriteModel() {
        ChatParseResp response = new ChatParseResp(1L);
        response.setErrorMsg(
                "查询服务内部配置异常（SYSTEM_TRANSLATION_FAILED），请联系管理员或稍后重试。");
        response.setTerminalError(true);

        Agent agent = mock(Agent.class);
        when(agent.getChatAppConfig()).thenReturn(Map.of(ErrorMsgRewriteProcessor.APP_KEY,
                ChatApp.builder().enable(true).build()));
        ParseContext context = mock(ParseContext.class);
        when(context.getResponse()).thenReturn(response);
        when(context.getAgent()).thenReturn(agent);

        assertFalse(new ErrorMsgRewriteProcessor().accept(context));
    }
}
