package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.enums.TagDefineType;
import com.tencent.supersonic.headless.api.pojo.request.TagReq;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.TagDO;
import com.tencent.supersonic.headless.server.persistence.repository.TagRepository;
import com.tencent.supersonic.headless.server.pojo.TagFilter;
import com.tencent.supersonic.headless.server.service.impl.TagMetaServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TagMetaServiceAccessTest {

    private final TagRepository repository = mock(TagRepository.class);
    private final ModelService modelService = mock(ModelService.class);
    private final CollectService collectService = mock(CollectService.class);
    private final DimensionService dimensionService = mock(DimensionService.class);
    private final MetricService metricService = mock(MetricService.class);
    private final TagObjectService tagObjectService = mock(TagObjectService.class);
    private final DomainService domainService = mock(DomainService.class);
    private final TagMetaServiceImpl service = new TagMetaServiceImpl(repository, modelService,
            collectService, dimensionService, metricService, tagObjectService, domainService);

    @Test
    void createRejectsUserWithoutRelatedModelAdministration() {
        when(metricService.getMetric(1L)).thenReturn(metric(1L, 10L));
        when(modelService.getModelListWithAuth(any(User.class), isNull(), any(AuthType.class)))
                .thenReturn(List.of());
        TagReq request = new TagReq();
        request.setTagDefineType(TagDefineType.METRIC);
        request.setItemId(1L);

        assertThrows(InvalidPermissionException.class,
                () -> service.create(request, User.get(2L, "analyst")));

        verifyNoInteractions(repository);
    }

    @Test
    void oversizedBatchFailsBeforeAuthorizationOrPersistence() {
        assertThrows(InvalidArgumentException.class, () -> service
                .createBatch(Collections.nCopies(1_001, new TagReq()), User.get(2L, "analyst")));

        verifyNoInteractions(repository, modelService, metricService, dimensionService);
    }

    @Test
    void visitIdentityCannotEnumerateRawTagConfiguration() {
        assertThrows(InvalidPermissionException.class,
                () -> service.getTagDOList(new TagFilter(), User.getVisitUser()));

        verifyNoInteractions(repository, modelService, metricService, dimensionService);
    }

    @Test
    void rawTagQueryOnlyReturnsModelsAdministeredByUser() {
        ModelResp administered = new ModelResp();
        administered.setId(10L);
        when(modelService.getModelListWithAuth(any(User.class), isNull(), any(AuthType.class)))
                .thenReturn(List.of(administered));
        when(repository.getTagDOList(any(TagFilter.class)))
                .thenReturn(List.of(tag(1L, 101L), tag(2L, 102L)));
        when(metricService.getMetric(101L)).thenReturn(metric(101L, 10L));
        when(metricService.getMetric(102L)).thenReturn(metric(102L, 20L));

        List<TagDO> tags = service.getTagDOList(new TagFilter(), User.get(2L, "analyst"));

        assertEquals(List.of(1L), tags.stream().map(TagDO::getId).toList());
    }

    @Test
    void deleteRejectsUserWithoutRelatedModelAdministration() {
        when(repository.getTagById(1L)).thenReturn(tag(1L, 101L));
        when(metricService.getMetric(101L)).thenReturn(metric(101L, 10L));
        when(modelService.getModelListWithAuth(any(User.class), isNull(), any(AuthType.class)))
                .thenReturn(List.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.delete(1L, User.get(2L, "analyst")));
    }

    private TagDO tag(Long id, Long itemId) {
        TagDO tag = new TagDO();
        tag.setId(id);
        tag.setItemId(itemId);
        tag.setType(TagDefineType.METRIC.name());
        return tag;
    }

    private MetricResp metric(Long id, Long modelId) {
        MetricResp metric = new MetricResp();
        metric.setId(id);
        metric.setModelId(modelId);
        return metric;
    }
}
