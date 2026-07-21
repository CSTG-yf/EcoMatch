package com.tencent.supersonic.headless.server.service.governance;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.PublishEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.MetricDefineByFieldParams;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.server.governance.MetricGovernanceStatus;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricGovernanceDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricVersionDO;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricApprovalMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricGovernanceMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricOrgMappingMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricVersionMapper;
import com.tencent.supersonic.headless.server.pojo.governance.MetricGovernanceDetail;
import com.tencent.supersonic.headless.server.pojo.governance.MetricVersionSnapshot;
import com.tencent.supersonic.headless.server.service.MetricService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricGovernanceServiceTest {

    private MetricGovernanceMapper governanceMapper;
    private MetricVersionMapper versionMapper;
    private MetricApprovalMapper approvalMapper;
    private MetricOrgMappingMapper mappingMapper;
    private MetricService metricService;
    private MetricGovernanceService service;

    @BeforeEach
    void setUp() {
        governanceMapper = mock(MetricGovernanceMapper.class);
        versionMapper = mock(MetricVersionMapper.class);
        approvalMapper = mock(MetricApprovalMapper.class);
        mappingMapper = mock(MetricOrgMappingMapper.class);
        metricService = mock(MetricService.class);
        service = new MetricGovernanceService(governanceMapper, versionMapper, approvalMapper,
                mappingMapper, metricService);
    }

    @Test
    void shouldRestoreMetricAndCreateNewDraftVersion() throws Exception {
        MetricResp metric = metric();
        MetricGovernanceDO governance = new MetricGovernanceDO();
        governance.setId(1L);
        governance.setMetricId(101L);
        governance.setCurrentVersion(2);
        governance.setGovernanceStatus(MetricGovernanceStatus.PUBLISHED.name());
        MetricVersionDO target = version(metric, governance);
        target.setId(10L);
        target.setVersionNo(1);

        when(versionMapper.selectOne(any())).thenReturn(target);
        when(versionMapper.selectList(any())).thenReturn(Collections.singletonList(target));
        when(governanceMapper.selectOne(any())).thenReturn(governance);
        when(metricService.getMetric(101L, User.getDefaultUser())).thenReturn(metric);
        doAnswer(invocation -> {
            ((MetricVersionDO) invocation.getArgument(0)).setId(11L);
            return 1;
        }).when(versionMapper).insert(any(MetricVersionDO.class));

        MetricVersionDO restored = service.rollback(101L, 1, "恢复稳定口径", User.getDefaultUser());

        ArgumentCaptor<MetricReq> request = ArgumentCaptor.forClass(MetricReq.class);
        verify(metricService).updateMetric(request.capture(), any());
        assertEquals("SUM(metric_value)",
                request.getValue().getMetricDefineByFieldParams().getExpr());
        assertEquals(2, restored.getVersionNo());
        assertEquals(MetricGovernanceStatus.DRAFT.name(), governance.getGovernanceStatus());
        verify(metricService).batchUnPublish(Collections.singletonList(101L),
                User.getDefaultUser());
    }

    @Test
    void shouldPublishApprovedVersion() {
        MetricVersionDO version = new MetricVersionDO();
        version.setId(10L);
        version.setMetricId(101L);
        version.setVersionNo(2);
        version.setApprovalStatus("APPROVED");
        MetricGovernanceDO governance = new MetricGovernanceDO();
        governance.setMetricId(101L);
        governance.setCurrentVersion(2);
        when(versionMapper.selectById(10L)).thenReturn(version);
        when(governanceMapper.selectOne(any())).thenReturn(governance);
        when(metricService.getMetric(eq(101L), any(User.class))).thenReturn(metric());
        when(versionMapper.selectList(any())).thenReturn(Collections.singletonList(version));
        when(approvalMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(mappingMapper.selectList(any())).thenReturn(Collections.emptyList());

        MetricGovernanceDetail detail = service.publish(101L, 10L, "审批通过", User.getDefaultUser());

        assertEquals(MetricGovernanceStatus.PUBLISHED.name(),
                detail.getGovernance().getGovernanceStatus());
        verify(metricService).batchPublish(eq(Collections.singletonList(101L)), any(User.class));
    }

    @Test
    void shouldRejectPublishingPendingVersion() {
        MetricVersionDO version = new MetricVersionDO();
        version.setMetricId(101L);
        version.setApprovalStatus("PENDING");
        when(versionMapper.selectById(10L)).thenReturn(version);

        assertThrows(InvalidArgumentException.class,
                () -> service.publish(101L, 10L, null, User.getDefaultUser()));
    }

    private MetricVersionDO version(MetricResp metric, MetricGovernanceDO governance) {
        MetricVersionSnapshot snapshot = new MetricVersionSnapshot();
        snapshot.setMetric(metric);
        snapshot.setOwnerDepartment("计划财务部");
        snapshot.setSourceSystem("监管报送系统");
        snapshot.setBusinessDefinition("各项存款期末余额");
        MetricVersionDO version = new MetricVersionDO();
        version.setMetricId(metric.getId());
        version.setSnapshotJson(JsonUtil.toString(snapshot));
        return version;
    }

    private MetricResp metric() {
        MetricResp metric = new MetricResp();
        metric.setId(101L);
        metric.setModelId(10L);
        metric.setName("各项存款余额");
        metric.setBizName("zb001");
        metric.setIsPublish(PublishEnum.PUBLISHED.getCode());
        metric.setMetricDefineType(MetricDefineType.FIELD);
        MetricDefineByFieldParams params = new MetricDefineByFieldParams();
        params.setExpr("SUM(metric_value)");
        metric.setMetricDefineByFieldParams(params);
        return metric;
    }
}
