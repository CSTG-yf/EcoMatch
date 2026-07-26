package com.tencent.supersonic.chat.server.service.impl;

import com.google.common.collect.Lists;
import com.tencent.supersonic.chat.api.pojo.request.ChatAggConfigReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatConfigBaseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatConfigEditReqReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatConfigFilter;
import com.tencent.supersonic.chat.api.pojo.request.ChatDefaultConfigReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatDetailConfigReq;
import com.tencent.supersonic.chat.api.pojo.request.ItemNameVisibilityInfo;
import com.tencent.supersonic.chat.api.pojo.request.ItemVisibility;
import com.tencent.supersonic.chat.api.pojo.request.KnowledgeInfoReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatAggRichConfigResp;
import com.tencent.supersonic.chat.api.pojo.response.ChatConfigResp;
import com.tencent.supersonic.chat.api.pojo.response.ChatConfigRichResp;
import com.tencent.supersonic.chat.api.pojo.response.ChatDefaultRichConfigResp;
import com.tencent.supersonic.chat.api.pojo.response.ChatDetailRichConfigResp;
import com.tencent.supersonic.chat.api.pojo.response.ItemVisibilityInfo;
import com.tencent.supersonic.chat.server.config.ChatConfig;
import com.tencent.supersonic.chat.server.persistence.repository.ChatConfigRepository;
import com.tencent.supersonic.chat.server.service.ConfigService;
import com.tencent.supersonic.chat.server.util.ChatConfigHelper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.service.ModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConfigServiceImpl implements ConfigService {

    private final ChatConfigRepository chatConfigRepository;
    private final ChatConfigHelper chatConfigHelper;
    private final SemanticLayerService semanticLayerService;
    private final ModelService modelService;

    public ConfigServiceImpl(ChatConfigRepository chatConfigRepository,
            ChatConfigHelper chatConfigHelper, SemanticLayerService semanticLayerService,
            ModelService modelService) {
        this.chatConfigRepository = chatConfigRepository;
        this.chatConfigHelper = chatConfigHelper;
        this.semanticLayerService = semanticLayerService;
        this.modelService = modelService;
    }

    @Override
    public Long addConfig(ChatConfigBaseReq configBaseCmd, User user) {
        requireModelAccess(configBaseCmd == null ? null : configBaseCmd.getModelId(), user,
                AuthType.ADMIN);
        log.info("[create model extend] request=[{}]", SensitiveLogUtils.summarize(configBaseCmd));
        duplicateCheck(configBaseCmd.getModelId());
        ChatConfig chaConfig = chatConfigHelper.newChatConfig(configBaseCmd, user);
        return chatConfigRepository.createConfig(chaConfig);
    }

    private void duplicateCheck(Long modelId) {
        ChatConfigFilter filter = new ChatConfigFilter();
        filter.setModelId(modelId);
        List<ChatConfigResp> chaConfigDescList = chatConfigRepository.getChatConfig(filter);
        if (!CollectionUtils.isEmpty(chaConfigDescList)) {
            throw new RuntimeException("chat config existed, no need to add repeatedly");
        }
    }

    @Override
    public Long editConfig(ChatConfigEditReqReq configEditCmd, User user) {
        log.info("[edit model extend] request=[{}]", SensitiveLogUtils.summarize(configEditCmd));
        if (Objects.isNull(configEditCmd) || Objects.isNull(configEditCmd.getId())
                && Objects.isNull(configEditCmd.getModelId())) {
            throw new InvalidArgumentException(
                    "editConfig, id and modelId are not allowed to be empty at the same time");
        }
        Long modelId = resolveEditModelId(configEditCmd);
        requireModelAccess(modelId, user, AuthType.ADMIN);
        configEditCmd.setModelId(modelId);
        ChatConfig chaConfig = chatConfigHelper.editChatConfig(configEditCmd, user);
        chatConfigRepository.updateConfig(chaConfig);
        return configEditCmd.getId();
    }

    public ItemNameVisibilityInfo getItemNameVisibility(ChatConfig chatConfig) {
        Long modelId = chatConfig.getModelId();

        List<Long> blackDimIdList = new ArrayList<>();
        if (Objects.nonNull(chatConfig.getChatAggConfig())
                && Objects.nonNull(chatConfig.getChatAggConfig().getVisibility())) {
            blackDimIdList
                    .addAll(chatConfig.getChatAggConfig().getVisibility().getBlackDimIdList());
        }
        if (Objects.nonNull(chatConfig.getChatDetailConfig())
                && Objects.nonNull(chatConfig.getChatDetailConfig().getVisibility())) {
            blackDimIdList
                    .addAll(chatConfig.getChatDetailConfig().getVisibility().getBlackDimIdList());
        }
        List<Long> filterDimIdList =
                blackDimIdList.stream().distinct().collect(Collectors.toList());

        List<Long> blackMetricIdList = new ArrayList<>();
        if (Objects.nonNull(chatConfig.getChatAggConfig())
                && Objects.nonNull(chatConfig.getChatAggConfig().getVisibility())) {
            blackMetricIdList
                    .addAll(chatConfig.getChatAggConfig().getVisibility().getBlackMetricIdList());
        }
        if (Objects.nonNull(chatConfig.getChatDetailConfig())
                && Objects.nonNull(chatConfig.getChatDetailConfig().getVisibility())) {
            blackMetricIdList.addAll(
                    chatConfig.getChatDetailConfig().getVisibility().getBlackMetricIdList());
        }
        List<Long> filterMetricIdList =
                blackMetricIdList.stream().distinct().collect(Collectors.toList());

        ItemNameVisibilityInfo itemNameVisibility = new ItemNameVisibilityInfo();
        MetaFilter metaFilter = new MetaFilter();
        metaFilter.setModelIds(Lists.newArrayList(modelId));
        if (!CollectionUtils.isEmpty(blackDimIdList)) {
            List<DimensionResp> dimensionRespList = semanticLayerService.getDimensions(metaFilter);
            List<String> blackDimNameList =
                    dimensionRespList.stream().filter(o -> filterDimIdList.contains(o.getId()))
                            .map(SchemaItem::getName).collect(Collectors.toList());
            itemNameVisibility.setBlackDimNameList(blackDimNameList);
        }
        if (!CollectionUtils.isEmpty(blackMetricIdList)) {

            List<MetricResp> metricRespList = semanticLayerService.getMetrics(metaFilter);
            List<String> blackMetricList =
                    metricRespList.stream().filter(o -> filterMetricIdList.contains(o.getId()))
                            .map(SchemaItem::getName).collect(Collectors.toList());
            itemNameVisibility.setBlackMetricNameList(blackMetricList);
        }
        return itemNameVisibility;
    }

    @Override
    public List<ChatConfigResp> search(ChatConfigFilter filter, User user) {
        log.info("[search model extend] request=[{}]", SensitiveLogUtils.summarize(filter));
        Set<Long> manageableModelIds = authorizedModelIds(user, AuthType.ADMIN);
        List<ChatConfigResp> chaConfigDescList = chatConfigRepository.getChatConfig(filter);
        return chaConfigDescList.stream()
                .filter(config -> manageableModelIds.contains(config.getModelId())).toList();
    }

    @Override
    public ChatConfigResp fetchConfigByModelId(Long modelId) {
        return chatConfigRepository.getConfigByModelId(modelId);
    }

    private ItemVisibilityInfo fetchVisibilityDescByConfig(ItemVisibility visibility,
            DataSetSchema modelSchema) {
        ItemVisibilityInfo itemVisibilityDesc = new ItemVisibilityInfo();

        List<Long> dimIdAllList = chatConfigHelper.generateAllDimIdList(modelSchema);
        List<Long> metricIdAllList = chatConfigHelper.generateAllMetricIdList(modelSchema);

        List<Long> blackDimIdList = new ArrayList<>();
        List<Long> blackMetricIdList = new ArrayList<>();
        if (Objects.nonNull(visibility)) {
            if (!CollectionUtils.isEmpty(visibility.getBlackDimIdList())) {
                blackDimIdList.addAll(visibility.getBlackDimIdList());
            }
            if (!CollectionUtils.isEmpty(visibility.getBlackMetricIdList())) {
                blackMetricIdList.addAll(visibility.getBlackMetricIdList());
            }
        }
        List<Long> whiteMetricIdList = metricIdAllList.stream()
                .filter(id -> !blackMetricIdList.contains(id) && metricIdAllList.contains(id))
                .collect(Collectors.toList());
        List<Long> whiteDimIdList = dimIdAllList.stream()
                .filter(id -> !blackDimIdList.contains(id) && dimIdAllList.contains(id))
                .collect(Collectors.toList());

        itemVisibilityDesc.setBlackDimIdList(blackDimIdList);
        itemVisibilityDesc.setBlackMetricIdList(blackMetricIdList);
        itemVisibilityDesc.setWhiteDimIdList(
                Objects.isNull(whiteDimIdList) ? new ArrayList<>() : whiteDimIdList);
        itemVisibilityDesc.setWhiteMetricIdList(
                Objects.isNull(whiteMetricIdList) ? new ArrayList<>() : whiteMetricIdList);

        return itemVisibilityDesc;
    }

    @Override
    public ChatConfigRichResp getConfigRichInfo(Long modelId, User user) {
        requireModelAccess(modelId, user, AuthType.ADMIN);
        ChatConfigRichResp chatConfigRich = new ChatConfigRichResp();
        ChatConfigResp chatConfigResp = chatConfigRepository.getConfigByModelId(modelId);
        if (Objects.isNull(chatConfigResp)) {
            log.info("there is no chatConfigDesc for modelId:{}", modelId);
            return chatConfigRich;
        }
        BeanUtils.copyProperties(chatConfigResp, chatConfigRich);

        DataSetSchema dataSetSchema = semanticLayerService.getDataSetSchema(modelId);
        if (dataSetSchema == null) {
            return chatConfigRich;
        }
        chatConfigRich.setBizName(dataSetSchema.getDataSet().getBizName());
        chatConfigRich.setModelName(dataSetSchema.getDataSet().getName());

        chatConfigRich.setChatAggRichConfig(fillChatAggRichConfig(dataSetSchema, chatConfigResp));
        chatConfigRich.setChatDetailRichConfig(
                fillChatDetailRichConfig(dataSetSchema, chatConfigRich, chatConfigResp));

        return chatConfigRich;
    }

    @Override
    public DataSetSchema getDataSetSchema(Long modelId, User user) {
        requireModelAccess(modelId, user, AuthType.VIEWER);
        return semanticLayerService.getDataSetSchema(modelId);
    }

    private ChatDetailRichConfigResp fillChatDetailRichConfig(DataSetSchema modelSchema,
            ChatConfigRichResp chatConfigRich, ChatConfigResp chatConfigResp) {
        if (Objects.isNull(chatConfigResp)
                || Objects.isNull(chatConfigResp.getChatDetailConfig())) {
            return null;
        }
        ChatDetailRichConfigResp detailRichConfig = new ChatDetailRichConfigResp();
        ChatDetailConfigReq chatDetailConfig = chatConfigResp.getChatDetailConfig();
        ItemVisibilityInfo itemVisibilityInfo =
                fetchVisibilityDescByConfig(chatDetailConfig.getVisibility(), modelSchema);
        detailRichConfig.setVisibility(itemVisibilityInfo);
        detailRichConfig.setKnowledgeInfos(
                fillKnowledgeBizName(chatDetailConfig.getKnowledgeInfos(), modelSchema));
        detailRichConfig.setGlobalKnowledgeConfig(chatDetailConfig.getGlobalKnowledgeConfig());
        detailRichConfig.setChatDefaultConfig(fetchDefaultConfig(
                chatDetailConfig.getChatDefaultConfig(), modelSchema, itemVisibilityInfo));

        return detailRichConfig;
    }

    private ChatAggRichConfigResp fillChatAggRichConfig(DataSetSchema modelSchema,
            ChatConfigResp chatConfigResp) {
        if (Objects.isNull(chatConfigResp) || Objects.isNull(chatConfigResp.getChatAggConfig())) {
            return null;
        }
        ChatAggConfigReq chatAggConfig = chatConfigResp.getChatAggConfig();
        ChatAggRichConfigResp chatAggRichConfig = new ChatAggRichConfigResp();
        ItemVisibilityInfo itemVisibilityInfo =
                fetchVisibilityDescByConfig(chatAggConfig.getVisibility(), modelSchema);
        chatAggRichConfig.setVisibility(itemVisibilityInfo);
        chatAggRichConfig.setKnowledgeInfos(
                fillKnowledgeBizName(chatAggConfig.getKnowledgeInfos(), modelSchema));
        chatAggRichConfig.setGlobalKnowledgeConfig(chatAggConfig.getGlobalKnowledgeConfig());
        chatAggRichConfig.setChatDefaultConfig(fetchDefaultConfig(
                chatAggConfig.getChatDefaultConfig(), modelSchema, itemVisibilityInfo));

        return chatAggRichConfig;
    }

    private ChatDefaultRichConfigResp fetchDefaultConfig(ChatDefaultConfigReq chatDefaultConfig,
            DataSetSchema modelSchema, ItemVisibilityInfo itemVisibilityInfo) {
        ChatDefaultRichConfigResp defaultRichConfig = new ChatDefaultRichConfigResp();
        if (Objects.isNull(chatDefaultConfig)) {
            return defaultRichConfig;
        }
        BeanUtils.copyProperties(chatDefaultConfig, defaultRichConfig);
        Map<Long, SchemaElement> dimIdAndRespPair = modelSchema.getDimensions().stream().collect(
                Collectors.toMap(SchemaElement::getId, Function.identity(), (k1, k2) -> k1));

        Map<Long, SchemaElement> metricIdAndRespPair = modelSchema.getMetrics().stream().collect(
                Collectors.toMap(SchemaElement::getId, Function.identity(), (k1, k2) -> k1));

        List<SchemaElement> dimensions = new ArrayList<>();
        List<SchemaElement> metrics = new ArrayList<>();
        if (!CollectionUtils.isEmpty(chatDefaultConfig.getDimensionIds())) {
            chatDefaultConfig.getDimensionIds().stream()
                    .filter(dimId -> dimIdAndRespPair.containsKey(dimId)
                            && itemVisibilityInfo.getWhiteDimIdList().contains(dimId))
                    .forEach(dimId -> {
                        SchemaElement dimSchemaResp = dimIdAndRespPair.get(dimId);
                        if (Objects.nonNull(dimSchemaResp)) {
                            SchemaElement dimSchema = new SchemaElement();
                            BeanUtils.copyProperties(dimSchemaResp, dimSchema);
                            dimensions.add(dimSchema);
                        }
                    });
        }

        if (!CollectionUtils.isEmpty(chatDefaultConfig.getMetricIds())) {
            chatDefaultConfig.getMetricIds().stream()
                    .filter(metricId -> metricIdAndRespPair.containsKey(metricId)
                            && itemVisibilityInfo.getWhiteMetricIdList().contains(metricId))
                    .forEach(metricId -> {
                        SchemaElement metricSchemaResp = metricIdAndRespPair.get(metricId);
                        if (Objects.nonNull(metricSchemaResp)) {
                            SchemaElement metricSchema = new SchemaElement();
                            BeanUtils.copyProperties(metricSchemaResp, metricSchema);
                            metrics.add(metricSchema);
                        }
                    });
        }

        defaultRichConfig.setDimensions(dimensions);
        defaultRichConfig.setMetrics(metrics);
        return defaultRichConfig;
    }

    private List<KnowledgeInfoReq> fillKnowledgeBizName(List<KnowledgeInfoReq> knowledgeInfos,
            DataSetSchema modelSchema) {
        if (CollectionUtils.isEmpty(knowledgeInfos)) {
            return new ArrayList<>();
        }
        Map<Long, SchemaElement> dimIdAndRespPair = modelSchema.getDimensions().stream().collect(
                Collectors.toMap(SchemaElement::getId, Function.identity(), (k1, k2) -> k1));
        knowledgeInfos.forEach(knowledgeInfo -> {
            if (Objects.nonNull(knowledgeInfo)) {
                SchemaElement dimSchemaResp = dimIdAndRespPair.get(knowledgeInfo.getItemId());
                if (Objects.nonNull(dimSchemaResp)) {
                    knowledgeInfo.setBizName(dimSchemaResp.getBizName());
                }
            }
        });
        return knowledgeInfos;
    }

    @Override
    public List<ChatConfigRichResp> getAllChatRichConfig(User user) {
        return authorizedModelIds(user, AuthType.ADMIN).stream()
                .map(modelId -> getConfigRichInfo(modelId, user))
                .filter(config -> config.getModelId() != null).toList();
    }

    private Long resolveEditModelId(ChatConfigEditReqReq request) {
        if (request.getId() == null) {
            return request.getModelId();
        }
        ChatConfigFilter filter = new ChatConfigFilter();
        filter.setId(request.getId());
        List<ChatConfigResp> storedConfigs = chatConfigRepository.getChatConfig(filter);
        if (CollectionUtils.isEmpty(storedConfigs)) {
            throw new InvalidArgumentException("Chat config does not exist");
        }
        Long storedModelId = storedConfigs.get(0).getModelId();
        if (request.getModelId() != null && !Objects.equals(request.getModelId(), storedModelId)) {
            throw new InvalidArgumentException("Chat config model binding cannot be changed");
        }
        return storedModelId;
    }

    private void requireModelAccess(Long modelId, User user, AuthType authType) {
        if (modelId == null) {
            throw new InvalidArgumentException("Model id is required");
        }
        if (!authorizedModelIds(user, authType).contains(modelId)) {
            throw new InvalidPermissionException("No permission to access model " + modelId);
        }
    }

    private Set<Long> authorizedModelIds(User user, AuthType authType) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("User identity is required");
        }
        return modelService.getModelListWithAuth(user, null, authType).stream()
                .map(ModelResp::getId).collect(Collectors.toSet());
    }
}
