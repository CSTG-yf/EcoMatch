package com.tencent.supersonic.chat.server.persistence.repository;

import com.tencent.supersonic.chat.server.persistence.dataobject.ChatDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.QueryDO;

import java.util.List;

public interface ChatRepository {

    Long createChat(ChatDO chatDO);

    ChatDO getChat(Long chatId);

    List<ChatDO> getAll(String creator, Integer agentId);

    Boolean updateChatName(Long chatId, String chatName, String lastTime, String creator);

    Boolean updateLastQuestion(Long chatId, String lastQuestion, String lastTime);

    Boolean updateConversionIsTop(Long chatId, int isTop);

    boolean updateFeedback(QueryDO queryDO);

    Boolean deleteChat(Long chatId, String userName);
}
