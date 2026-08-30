package com.tencent.supersonic.chat.server.persistence.repository.impl;

import com.tencent.supersonic.chat.server.persistence.dataobject.ChatContextDO;
import com.tencent.supersonic.chat.server.persistence.mapper.ChatContextMapper;
import com.tencent.supersonic.chat.server.pojo.ChatContext;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatContextRepositoryImplTest {

    @Mock
    private ChatContextMapper chatContextMapper;

    @Test
    void persistsParseContextContainingLocalDateOnJava21() {
        ChatContextRepositoryImpl repository = new ChatContextRepositoryImpl(chatContextMapper);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getProperties().put("planStartDate", LocalDate.of(2026, 3, 31));
        ChatContext context = new ChatContext();
        context.setChatId(33);
        context.setParseInfo(parseInfo);

        repository.updateContext(context);

        ArgumentCaptor<ChatContextDO> captured = ArgumentCaptor.forClass(ChatContextDO.class);
        verify(chatContextMapper).insertOrUpdate(captured.capture());
        assertTrue(captured.getValue().getSemanticParse().contains("\"2026-03-31\""));
    }
}
