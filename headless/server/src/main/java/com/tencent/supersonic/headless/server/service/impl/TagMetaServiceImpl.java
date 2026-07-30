package com.tencent.supersonic.headless.server.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.enums.TagDefineType;
import com.tencent.supersonic.headless.api.pojo.request.TagDeleteReq;
import com.tencent.supersonic.headless.api.pojo.request.TagFilterPageReq;
import com.tencent.supersonic.headless.api.pojo.request.TagReq;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.TagItem;
import com.tencent.supersonic.headless.api.pojo.response.TagObjectResp;
import com.tencent.supersonic.headless.api.pojo.response.TagResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.CollectDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.TagDO;
import com.tencent.supersonic.headless.server.persistence.repository.TagRepository;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.pojo.TagFilter;
import com.tencent.supersonic.headless.server.pojo.TagObjectFilter;
import com.tencent.supersonic.headless.server.service.CollectService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TagMetaService;
import com.tencent.supersonic.headless.server.service.TagObjectService;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TagMetaServiceImpl implements TagMetaService {

    private static final int MAX_BATCH_TAGS = 1_000;

    private final TagRepository tagRepository;
    private final ModelService modelService;
    private final CollectService collectService;
    private final DimensionService dimensionService;
    private final MetricService metricService;
    private final TagObjectService tagObjectService;
    private final DomainService domainService;

    public TagMetaServiceImpl(TagRepository tagRepository, ModelService modelService,
            CollectService collectService, @Lazy DimensionService dimensionService,
            @Lazy MetricService metricService, TagObjectService tagObjectService,
            DomainService domainService) {
        this.tagRepository = tagRepository;
        this.modelService = modelService;
        this.collectService = collectService;
        this.dimensionService = dimensionService;
        this.metricService = metricService;
        this.tagObjectService = tagObjectService;
        this.domainService = domainService;
    }

    @Override
    public TagResp create(TagReq tagReq, User user) {
        requireModelAdmin(resolveModelId(tagReq), user);
        checkExist(tagReq);
        checkTagObject(tagReq);
        TagDO tagDO = convert(tagReq);
        Date date = new Date();
        tagDO.setId(null);
        tagDO.setCreatedBy(user.getName());
        tagDO.setCreatedAt(date);
        tagDO.setUpdatedBy(user.getName());
        tagDO.setUpdatedAt(date);
        tagRepository.create(tagDO);
        return getTag(tagDO.getId(), user);
    }

    @Override
    @Transactional
    public Integer createBatch(List<TagReq> tagReqList, User user) {
        if (CollectionUtils.isEmpty(tagReqList)) {
            throw new InvalidArgumentException("Tag requests must not be empty");
        }
        if (tagReqList.size() > MAX_BATCH_TAGS) {
            throw new InvalidArgumentException(
                    "Tag request count exceeds maximum: " + MAX_BATCH_TAGS);
        }
        tagReqList.forEach(tagReq -> requireModelAdmin(resolveModelId(tagReq), user));
        for (TagReq tagReq : tagReqList) {
            create(tagReq, user);
        }
        return tagReqList.size();
    }

    @Override
    public Boolean delete(Long id, User user) {
        TagDO tag = getRequiredTag(id);
        requireModelAdmin(resolveModelId(tag), user);
        tagRepository.delete(id);
        return true;
    }

    @Override
    @Transactional
    public Boolean deleteBatch(List<TagDeleteReq> tagDeleteReqList, User user) {
        if (CollectionUtils.isEmpty(tagDeleteReqList)) {
            throw new InvalidArgumentException("Tag delete requests must not be empty");
        }
        if (tagDeleteReqList.size() > MAX_BATCH_TAGS) {
            throw new InvalidArgumentException(
                    "Tag delete request count exceeds maximum: " + MAX_BATCH_TAGS);
        }
        List<List<TagDO>> tagsByRequest =
                tagDeleteReqList.stream().map(this::getRequiredTags).collect(Collectors.toList());
        tagsByRequest.stream().flatMap(List::stream)
                .forEach(tag -> requireModelAdmin(resolveModelId(tag), user));
        for (TagDeleteReq tagDeleteReq : tagDeleteReqList) {
            tagRepository.deleteBatch(tagDeleteReq);
        }
        return true;
    }

    @Override
    public TagResp getTag(Long id, User user) {
        TagDO tagDO = getRequiredTag(id);
        requireModelAdmin(resolveModelId(tagDO), user);
        TagResp tagResp = convert2Resp(tagDO);
        List<TagResp> tagRespList = Arrays.asList(tagResp);
        fillModelInfo(tagRespList);
        fillDomainInfo(tagRespList);
        fillTagObjectInfo(tagRespList, user);
        fillCollectAndAdminInfo(tagRespList, user);
        return tagRespList.get(0);
    }

    @Override
    public List<TagResp> getTags(TagFilter tagFilter) {
        return tagRepository.queryTagRespList(tagFilter);
    }

    @Override
    public List<TagDO> getTagDOList(TagFilter tagFilter) {
        return tagRepository.getTagDOList(tagFilter);
    }

    @Override
    public List<TagDO> getTagDOList(TagFilter tagFilter, User user) {
        requireAuthenticatedUser(user);
        Set<Long> adminModelIds = getAdminModelIds(user);
        if (adminModelIds.isEmpty()) {
            return List.of();
        }
        return tagRepository.getTagDOList(tagFilter).stream()
                .filter(tag -> adminModelIds.contains(resolveModelId(tag)))
                .collect(Collectors.toList());
    }

    /**
     * 分页查询标签列表信息
     *
     * @param tagMarketPageReq
     * @param user
     * @return
     */
    @Override
    public PageInfo<TagResp> queryTagMarketPage(TagFilterPageReq tagMarketPageReq, User user) {
        List<ModelResp> modelRespList = getRelatedModel(tagMarketPageReq);
        if (CollectionUtils.isEmpty(modelRespList)) {
            return new PageInfo<>();
        }

        if (Objects.nonNull(tagMarketPageReq.getTagObjectId())) {
            modelRespList =
                    modelRespList.stream()
                            .filter(modelResp -> tagMarketPageReq.getTagObjectId()
                                    .equals(modelResp.getTagObjectId()))
                            .collect(Collectors.toList());
        }
        if (CollectionUtils.isEmpty(modelRespList)) {
            return new PageInfo<TagResp>();
        }
        List<Long> modelIds =
                modelRespList.stream().map(model -> model.getId()).collect(Collectors.toList());

        TagFilter tagFilter = new TagFilter();
        BeanUtils.copyProperties(tagMarketPageReq, tagFilter);
        List<CollectDO> collectList = collectService.getCollectionList(user.getName());
        if (tagMarketPageReq.isHasCollect()) {
            List<Long> collectIds = collectList.stream().filter(
                    collectDO -> SchemaElementType.TAG.name().equalsIgnoreCase(collectDO.getType()))
                    .map(CollectDO::getCollectId).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(collectIds)) {
                tagFilter.setIds(Lists.newArrayList(-1L));
            } else {
                tagFilter.setIds(collectIds);
            }
        }
        tagFilter.setModelIds(modelIds);
        PageInfo<TagResp> tagDOPageInfo =
                PageHelper.startPage(tagMarketPageReq.getCurrent(), tagMarketPageReq.getPageSize())
                        .doSelectPageInfo(() -> getTags(tagFilter));

        List<TagResp> tagRespList = tagDOPageInfo.getList();
        if (CollectionUtils.isEmpty(tagRespList)) {
            return tagDOPageInfo;
        }
        fillModelInfo(tagRespList);
        fillDomainInfo(tagRespList);
        fillTagObjectInfo(tagRespList, user);
        fillCollectAndAdminInfo(tagRespList, user);
        tagDOPageInfo.setList(tagRespList);
        return tagDOPageInfo;
    }

    private void fillTagObjectInfo(List<TagResp> tagRespList, User user) {
        TagObjectFilter filter = new TagObjectFilter();
        List<TagObjectResp> tagObjects = tagObjectService.getTagObjects(filter, user);
        if (CollectionUtils.isEmpty(tagObjects)) {
            return;
        }
        Map<Long, TagObjectResp> tagObjectMap = tagObjects.stream().collect(
                Collectors.toMap(TagObjectResp::getId, tagObject -> tagObject, (v1, v2) -> v2));
        if (CollectionUtils.isNotEmpty(tagRespList)) {
            tagRespList.stream().forEach(tagResp -> {
                if (tagObjectMap.containsKey(tagResp.getTagObjectId())) {
                    tagResp.setTagObjectName(tagObjectMap.get(tagResp.getTagObjectId()).getName());
                }
            });
        }
    }

    private TagResp fillTagObjectInfo(TagResp tagResp, User user) {
        Long modelId = tagResp.getModelId();
        ModelResp model = modelService.getModel(modelId);
        TagObjectResp tagObject = tagObjectService.getTagObject(model.getTagObjectId(), user);
        tagResp.setTagObjectId(tagObject.getId());
        tagResp.setTagObjectName(tagObject.getName());
        return tagResp;
    }

    private void fillDomainInfo(List<TagResp> tagRespList) {
        Map<Long, DomainResp> domainMap = domainService.getDomainList().stream()
                .collect(Collectors.toMap(DomainResp::getId, domain -> domain, (v1, v2) -> v2));
        if (CollectionUtils.isNotEmpty(tagRespList) && Objects.nonNull(domainMap)) {
            tagRespList.stream().forEach(tagResp -> {
                if (domainMap.containsKey(tagResp.getDomainId())) {
                    tagResp.setDomainName(domainMap.get(tagResp.getDomainId()).getName());
                }
            });
        }
    }

    private TagResp convert2Resp(TagDO tagDO) {
        TagResp tagResp = new TagResp();
        BeanUtils.copyProperties(tagDO, tagResp);
        tagResp.setTagDefineType(tagDO.getType());
        if (TagDefineType.METRIC.name().equalsIgnoreCase(tagDO.getType())) {
            MetricResp metric = metricService.getMetric(tagDO.getItemId());
            tagResp.setBizName(metric.getBizName());
            tagResp.setName(metric.getName());
            tagResp.setModelId(metric.getModelId());
            tagResp.setModelName(metric.getModelName());
            tagResp.setDomainId(metric.getDomainId());
            tagResp.setSensitiveLevel(metric.getSensitiveLevel());
            tagResp.setExt(metric.getExt());
        }
        if (TagDefineType.DIMENSION.name().equalsIgnoreCase(tagDO.getType())) {
            DimensionResp dimensionResp = dimensionService.getDimension(tagDO.getItemId());
            tagResp.setBizName(dimensionResp.getBizName());
            tagResp.setName(dimensionResp.getName());
            tagResp.setModelId(dimensionResp.getModelId());
            tagResp.setModelName(dimensionResp.getModelName());
            tagResp.setSensitiveLevel(dimensionResp.getSensitiveLevel());
            tagResp.setExt(dimensionResp.getExt());
        }

        return tagResp;
    }

    private List<ModelResp> getRelatedModel(TagFilterPageReq tagMarketPageReq) {
        List<ModelResp> modelRespList = new ArrayList<>();
        ModelFilter modelFilter = new ModelFilter();
        modelFilter.setDomainIds(tagMarketPageReq.getDomainIds());
        modelFilter.setIds(tagMarketPageReq.getModelIds());
        Map<Long, ModelResp> modelMap = modelService.getModelMap(modelFilter);
        for (Long modelId : modelMap.keySet()) {
            ModelResp modelResp = modelMap.get(modelId);
            if (Objects.isNull(modelResp)) {
                continue;
            }
            if (CollectionUtils.isNotEmpty(tagMarketPageReq.getDomainIds())) {
                if (!tagMarketPageReq.getDomainIds().contains(modelResp.getDomainId())) {
                    continue;
                }
            }
            if (CollectionUtils.isNotEmpty(tagMarketPageReq.getModelIds())) {
                if (!tagMarketPageReq.getModelIds().contains(modelResp.getId())) {
                    continue;
                }
            }
            modelRespList.add(modelResp);
        }
        return modelRespList;
    }

    private void fillModelInfo(List<TagResp> tagRespList) {
        List<Long> modelIds =
                tagRespList.stream().map(TagResp::getModelId).collect(Collectors.toList());
        ModelFilter modelFilter = new ModelFilter(false, modelIds);
        Map<Long, ModelResp> modelIdAndRespMap = modelService.getModelMap(modelFilter);
        tagRespList.stream().forEach(tagResp -> {
            if (Objects.nonNull(modelIdAndRespMap)
                    && modelIdAndRespMap.containsKey(tagResp.getModelId())) {
                tagResp.setModelName(modelIdAndRespMap.get(tagResp.getModelId()).getName());
                tagResp.setDomainId(modelIdAndRespMap.get(tagResp.getModelId()).getDomainId());
                tagResp.setTagObjectId(
                        modelIdAndRespMap.get(tagResp.getModelId()).getTagObjectId());
            }
        });
    }

    private TagResp fillCollectAndAdminInfo(TagResp tagResp, User user) {
        List<Long> collectIds = collectService.getCollectionList(user.getName()).stream()
                .filter(collectDO -> TypeEnums.TAG.name().equalsIgnoreCase(collectDO.getType()))
                .map(CollectDO::getCollectId).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(collectIds) && collectIds.contains(tagResp.getId())) {
            tagResp.setIsCollect(true);
        } else {
            tagResp.setIsCollect(false);
        }
        List<TagResp> tagRespList = Arrays.asList(tagResp);
        fillAdminRes(tagRespList, user);
        return tagRespList.get(0);
    }

    private TagResp fillCollectAndAdminInfo(List<TagResp> tagRespList, User user) {
        List<Long> collectIds = collectService.getCollectionList(user.getName()).stream()
                .filter(collectDO -> TypeEnums.TAG.name().equalsIgnoreCase(collectDO.getType()))
                .map(CollectDO::getCollectId).collect(Collectors.toList());

        tagRespList.stream().forEach(tagResp -> {
            if (CollectionUtils.isNotEmpty(collectIds) && collectIds.contains(tagResp.getId())) {
                tagResp.setIsCollect(true);
            } else {
                tagResp.setIsCollect(false);
            }
        });

        fillAdminRes(tagRespList, user);
        return tagRespList.get(0);
    }

    private void fillAdminRes(List<TagResp> tagRespList, User user) {
        List<ModelResp> modelRespList =
                modelService.getModelListWithAuth(user, null, AuthType.ADMIN);
        if (CollectionUtils.isEmpty(modelRespList)) {
            return;
        }
        Set<Long> modelIdSet =
                modelRespList.stream().map(ModelResp::getId).collect(Collectors.toSet());
        for (TagResp tagResp : tagRespList) {
            if (modelIdSet.contains(tagResp.getModelId())
                    || tagResp.getCreatedBy().equalsIgnoreCase(user.getName())) {
                tagResp.setHasAdminRes(true);
            } else {
                tagResp.setHasAdminRes(false);
            }
        }
    }

    private void checkExist(TagReq tagReq) {
        TagFilter tagFilter = new TagFilter();
        tagFilter.setTagDefineType(tagReq.getTagDefineType());
        if (Objects.nonNull(tagReq.getItemId())) {
            tagFilter.setItemIds(Arrays.asList(tagReq.getItemId()));
        }

        List<TagDO> tagRespList = tagRepository.getTagDOList(tagFilter);
        if (!CollectionUtils.isEmpty(tagRespList)) {
            throw new RuntimeException(
                    String.format("the tag is exit, itemId:%s", tagReq.getItemId()));
        }
    }

    private void checkTagObject(TagReq tagReq) {
        if (TagDefineType.DIMENSION.equals(tagReq.getTagDefineType())) {
            DimensionResp dimension = dimensionService.getDimension(tagReq.getItemId());
            ModelResp model = modelService.getModel(dimension.getModelId());
            if (Objects.isNull(model.getTagObjectId())) {
                throw new RuntimeException(
                        String.format("this dimension:%s is not supported to create tag,"
                                + " no related tag object", tagReq.getItemId()));
            }
        }
        if (TagDefineType.METRIC.equals(tagReq.getTagDefineType())) {
            MetricResp metric = metricService.getMetric(tagReq.getItemId());
            ModelResp model = modelService.getModel(metric.getModelId());
            if (Objects.isNull(model.getTagObjectId())) {
                throw new RuntimeException(String.format(
                        "this metric:%s is not supported to create tag," + " no related tag object",
                        tagReq.getItemId()));
            }
        }
    }

    private TagDO convert(TagReq tagReq) {
        TagDO tagDO = new TagDO();
        BeanUtils.copyProperties(tagReq, tagDO);
        tagDO.setType(tagReq.getTagDefineType().name());
        return tagDO;
    }

    @Override
    public List<TagItem> getTagItems(List<Long> itemIds, TagDefineType tagDefineType) {
        TagFilter tagFilter = new TagFilter();
        tagFilter.setTagDefineType(tagDefineType);
        tagFilter.setItemIds(itemIds);
        Set<Long> dimensionItemSet =
                getTagDOList(tagFilter).stream().map(TagDO::getItemId).collect(Collectors.toSet());
        return itemIds.stream().map(entry -> {
            TagItem tagItem = new TagItem();
            tagItem.setIsTag(Boolean.compare(dimensionItemSet.contains(entry), false));
            tagItem.setItemId(entry);
            return tagItem;
        }).collect(Collectors.toList());
    }

    private TagDO getRequiredTag(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidArgumentException("Tag id must be positive");
        }
        TagDO tag = tagRepository.getTagById(id);
        if (tag == null) {
            throw new InvalidArgumentException("Tag does not exist");
        }
        return tag;
    }

    private List<TagDO> getRequiredTags(TagDeleteReq request) {
        if (request == null || request.getTagDefineType() == null) {
            throw new InvalidArgumentException("Tag delete type is required");
        }
        Set<Long> ids = positiveIds(request.getIds(), "Tag ids");
        Set<Long> itemIds = positiveIds(request.getItemIds(), "Tag item ids");
        if (ids.isEmpty() && itemIds.isEmpty()) {
            throw new InvalidArgumentException("Tag ids or item ids must not be empty");
        }
        TagFilter filter = new TagFilter();
        filter.setIds(new ArrayList<>(ids));
        filter.setItemIds(new ArrayList<>(itemIds));
        filter.setTagDefineType(request.getTagDefineType());
        List<TagDO> tags = tagRepository.getTagDOList(filter);
        Set<Long> foundIds = tags.stream().map(TagDO::getId).collect(Collectors.toSet());
        Set<Long> foundItemIds = tags.stream().map(TagDO::getItemId).collect(Collectors.toSet());
        if (!foundIds.containsAll(ids) || !foundItemIds.containsAll(itemIds)) {
            throw new InvalidArgumentException("One or more tags do not exist");
        }
        return tags;
    }

    private Set<Long> positiveIds(List<Long> values, String field) {
        if (CollectionUtils.isEmpty(values)) {
            return Set.of();
        }
        if (values.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new InvalidArgumentException(field + " must be positive");
        }
        return Set.copyOf(values);
    }

    private Long resolveModelId(TagReq request) {
        if (request == null || request.getTagDefineType() == null || request.getItemId() == null
                || request.getItemId() <= 0) {
            throw new InvalidArgumentException("Tag type and positive item id are required");
        }
        return resolveModelId(request.getTagDefineType(), request.getItemId());
    }

    private Long resolveModelId(TagDO tag) {
        if (tag == null || tag.getType() == null || tag.getItemId() == null) {
            throw new InvalidArgumentException("Tag model ownership is incomplete");
        }
        try {
            return resolveModelId(TagDefineType.valueOf(tag.getType().toUpperCase(Locale.ROOT)),
                    tag.getItemId());
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException("Tag type is unsupported");
        }
    }

    private Long resolveModelId(TagDefineType type, Long itemId) {
        Long modelId;
        if (TagDefineType.METRIC.equals(type)) {
            MetricResp metric = metricService.getMetric(itemId);
            modelId = metric == null ? null : metric.getModelId();
        } else if (TagDefineType.DIMENSION.equals(type)) {
            DimensionResp dimension = dimensionService.getDimension(itemId);
            modelId = dimension == null ? null : dimension.getModelId();
        } else {
            throw new InvalidArgumentException("Tag type is unsupported");
        }
        if (modelId == null || modelId <= 0) {
            throw new InvalidArgumentException("Tag model ownership is missing");
        }
        return modelId;
    }

    private void requireModelAdmin(Long modelId, User user) {
        if (!getAdminModelIds(user).contains(modelId)) {
            throw new InvalidPermissionException("No permission to manage tag model");
        }
    }

    private Set<Long> getAdminModelIds(User user) {
        requireAuthenticatedUser(user);
        if (user.isSuperAdmin()) {
            List<ModelResp> models = modelService.getModelList(new MetaFilter());
            return CollectionUtils.isEmpty(models) ? Set.of()
                    : models.stream().map(ModelResp::getId).collect(Collectors.toSet());
        }
        List<ModelResp> models = modelService.getModelListWithAuth(user, null, AuthType.ADMIN);
        return CollectionUtils.isEmpty(models) ? Set.of()
                : models.stream().map(ModelResp::getId).collect(Collectors.toSet());
    }

    private void requireAuthenticatedUser(User user) {
        if (user == null || user.getName() == null || user.getName().isBlank()
                || User.getVisitUser().getName().equals(user.getName())) {
            throw new InvalidPermissionException("Authentication is required to manage tags");
        }
    }
}
