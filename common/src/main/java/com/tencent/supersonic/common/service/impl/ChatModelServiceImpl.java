package com.tencent.supersonic.common.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.persistence.dataobject.ChatModelDO;
import com.tencent.supersonic.common.persistence.mapper.ChatModelMapper;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatModelServiceImpl extends ServiceImpl<ChatModelMapper, ChatModelDO>
        implements ChatModelService {
    @Override
    public List<ChatModel> getChatModels(User user) {
        requireAuthenticatedUser(user);
        return list().stream().map(this::convert).filter(chatModel -> canView(user, chatModel))
                .map(chatModel -> user.isSuperAdmin() ? chatModel : redactCredentials(chatModel))
                .sorted(Comparator.comparingLong(ChatModel::getId)).collect(Collectors.toList());
    }

    @Override
    public ChatModel getChatModel(Integer id) {
        if (id == null) {
            return null;
        }
        return convert(getById(id));
    }

    @Override
    public ChatModel createChatModel(ChatModel chatModel, User user) {
        requireSuperAdmin(user);
        if (chatModel == null) {
            throw new InvalidArgumentException("Chat model is required");
        }
        ChatModelDO chatModelDO = convert(chatModel);
        chatModelDO.setCreatedBy(user.getName());
        chatModelDO.setCreatedAt(new Date());
        chatModelDO.setUpdatedBy(user.getName());
        chatModelDO.setUpdatedAt(chatModelDO.getCreatedAt());
        chatModelDO.setIsOpen(chatModel.getIsOpen());
        chatModelDO.setAdmin(user.getName());
        if (chatModel.getViewers() != null && !chatModel.getViewers().isEmpty()) {
            chatModelDO.setViewer(JsonUtil.toString(chatModel.getViewers()));
        }
        save(chatModelDO);
        return convert(chatModelDO);
    }

    @Override
    public ChatModel updateChatModel(ChatModel chatModel, User user) {
        requireSuperAdmin(user);
        if (chatModel == null || chatModel.getId() == null) {
            throw new InvalidArgumentException("Chat model id is required");
        }
        ChatModel existing = getChatModel(chatModel.getId());
        if (existing == null) {
            throw new InvalidArgumentException("Chat model does not exist");
        }
        ChatModelDO chatModelDO = convert(chatModel);
        chatModelDO.setCreatedBy(existing.getCreatedBy());
        chatModelDO.setCreatedAt(existing.getCreatedAt());
        chatModelDO.setUpdatedBy(user.getName());
        chatModelDO.setUpdatedAt(new Date());
        chatModelDO.setIsOpen(chatModel.getIsOpen());
        chatModelDO.setAdmin(StringUtils.defaultIfBlank(chatModel.getAdmin(), existing.getAdmin()));
        if (chatModel.getViewers() != null && !chatModel.getViewers().isEmpty()) {
            chatModelDO.setViewer(JsonUtil.toString(chatModel.getViewers()));
        }
        updateById(chatModelDO);
        return convert(chatModelDO);
    }

    @Override
    public void deleteChatModel(Integer id, User user) {
        requireSuperAdmin(user);
        ChatModel chatModel = getChatModel(id);
        if (chatModel == null) {
            throw new InvalidArgumentException("Chat model does not exist");
        }

        removeById(id);
    }

    private ChatModel convert(ChatModelDO chatModelDO) {
        if (chatModelDO == null) {
            return null;
        }
        ChatModel chatModel = new ChatModel();
        BeanUtils.copyProperties(chatModelDO, chatModel);
        chatModel.setConfig(JsonUtil.toObject(chatModelDO.getConfig(), ChatModelConfig.class));
        chatModel.setViewers(JsonUtil.toList(chatModelDO.getViewer(), String.class));
        return chatModel;
    }

    private ChatModelDO convert(ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }
        ChatModelDO chatModelDO = new ChatModelDO();
        BeanUtils.copyProperties(chatModel, chatModelDO);
        chatModelDO.setConfig(JsonUtil.toString(chatModel.getConfig()));
        return chatModelDO;
    }

    private boolean canView(User user, ChatModel chatModel) {
        return chatModel != null && (chatModel.isPublic() || user.isSuperAdmin()
                || StringUtils.equals(chatModel.getCreatedBy(), user.getName())
                || chatModel.getViewers() != null
                        && chatModel.getViewers().contains(user.getName()));
    }

    private ChatModel redactCredentials(ChatModel chatModel) {
        ChatModelConfig config = chatModel.getConfig();
        if (config != null) {
            config.setApiKey(null);
            config.setSecretKey(null);
        }
        return chatModel;
    }

    private void requireAuthenticatedUser(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("User identity is required");
        }
    }

    private void requireSuperAdmin(User user) {
        requireAuthenticatedUser(user);
        if (!user.isSuperAdmin()) {
            throw new InvalidPermissionException(
                    "Only super administrators can manage chat models");
        }
    }
}
