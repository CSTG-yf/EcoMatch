package com.tencent.supersonic.headless.server.service.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.PublishEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.server.governance.MetricApprovalAction;
import com.tencent.supersonic.headless.server.governance.MetricApprovalStatus;
import com.tencent.supersonic.headless.server.governance.MetricGovernanceStatus;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricApprovalDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricGovernanceDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricOrgMappingDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricVersionDO;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricApprovalMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricGovernanceMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricOrgMappingMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricVersionMapper;
import com.tencent.supersonic.headless.server.pojo.governance.MetricApprovalDecisionReq;
import com.tencent.supersonic.headless.server.pojo.governance.MetricGovernanceDetail;
import com.tencent.supersonic.headless.server.pojo.governance.MetricGovernanceReq;
import com.tencent.supersonic.headless.server.pojo.governance.MetricOrgMappingReq;
import com.tencent.supersonic.headless.server.pojo.governance.MetricVersionSnapshot;
import com.tencent.supersonic.headless.server.service.MetricService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class MetricGovernanceService {

    private final MetricGovernanceMapper governanceMapper;
    private final MetricVersionMapper versionMapper;
    private final MetricApprovalMapper approvalMapper;
    private final MetricOrgMappingMapper mappingMapper;
    private final MetricService metricService;

    public MetricGovernanceService(MetricGovernanceMapper governanceMapper,
            MetricVersionMapper versionMapper, MetricApprovalMapper approvalMapper,
            MetricOrgMappingMapper mappingMapper, MetricService metricService) {
        this.governanceMapper = governanceMapper;
        this.versionMapper = versionMapper;
        this.approvalMapper = approvalMapper;
        this.mappingMapper = mappingMapper;
        this.metricService = metricService;
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricGovernanceDetail updateGovernance(Long metricId, MetricGovernanceReq request,
            User user) {
        MetricResp metric = getMetric(metricId, user);
        validateEffectiveRange(request.getEffectiveFrom(), request.getEffectiveTo());
        MetricGovernanceDO governance = findGovernance(metricId);
        boolean initial = governance == null;
        if (initial) {
            governance = new MetricGovernanceDO();
            governance.setMetricId(metricId);
            governance.setCurrentVersion(0);
            governance.setCreatedAt(new Date());
            governance.setCreatedBy(user.getName());
        }
        boolean changed = initial || governanceChanged(governance, request);
        if (!initial && changed && hasPendingApproval(metricId)) {
            throw new InvalidArgumentException("指标存在待审批申请，不能创建新版本");
        }
        applyGovernance(governance, request, user);
        if (initial) {
            governance.setGovernanceStatus(initialStatus(metric));
            governanceMapper.insert(governance);
            createVersionInternal(metric, governance, "初始化治理档案", user,
                    initialApprovalStatus(metric), false);
        } else if (changed) {
            governance.setGovernanceStatus(MetricGovernanceStatus.DRAFT.name());
            governanceMapper.updateById(governance);
            createVersionInternal(metric, governance, defaultSummary(request), user,
                    MetricApprovalStatus.PENDING, true);
        }
        return detail(metricId, user);
    }

    @Transactional(rollbackFor = Exception.class)
    public int bootstrapModel(Long modelId, MetricGovernanceReq defaults, User user) {
        MetaFilter filter = new MetaFilter(Collections.singletonList(modelId));
        int created = 0;
        for (MetricResp metric : metricService.getMetrics(filter)) {
            if (findGovernance(metric.getId()) == null) {
                updateGovernance(metric.getId(), defaults, user);
                created++;
            }
        }
        return created;
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricVersionDO createVersion(Long metricId, String changeSummary, User user) {
        MetricResp metric = getMetric(metricId, user);
        MetricGovernanceDO governance = requireGovernance(metricId);
        if (hasPendingApproval(metricId)) {
            throw new InvalidArgumentException("指标存在待审批申请，不能创建新版本");
        }
        governance.setGovernanceStatus(MetricGovernanceStatus.DRAFT.name());
        governance.setUpdatedAt(new Date());
        governance.setUpdatedBy(user.getName());
        governanceMapper.updateById(governance);
        return createVersionInternal(metric, governance, changeSummary, user,
                MetricApprovalStatus.PENDING, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricApprovalDO submit(Long metricId, Long versionId, String comment, User user) {
        MetricGovernanceDO governance = requireGovernance(metricId);
        validateGovernanceReady(governance);
        MetricVersionDO version = requireVersion(metricId, versionId);
        requireCurrentVersion(governance, version);
        if (hasPendingApproval(metricId)) {
            throw new InvalidArgumentException("该指标已有待审批申请");
        }
        version.setApprovalStatus(MetricApprovalStatus.PENDING.name());
        versionMapper.updateById(version);
        governance.setCurrentVersion(version.getVersionNo());
        updateGovernanceStatus(governance, MetricGovernanceStatus.PENDING, user);

        MetricApprovalDO approval = approval(metricId, versionId, MetricApprovalAction.SUBMIT,
                MetricApprovalStatus.PENDING, comment, user);
        approvalMapper.insert(approval);
        return approval;
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricApprovalDO decide(Long approvalId, boolean approved,
            MetricApprovalDecisionReq request, User user) {
        MetricApprovalDO approval = approvalMapper.selectById(approvalId);
        if (approval == null
                || !MetricApprovalStatus.PENDING.name().equals(approval.getApprovalStatus())) {
            throw new InvalidArgumentException("审批记录不存在或已处理");
        }
        MetricVersionDO version = versionMapper.selectById(approval.getVersionId());
        MetricGovernanceDO governance = requireGovernance(approval.getMetricId());
        if (version == null) {
            throw new InvalidArgumentException("审批关联的指标版本不存在");
        }
        requireCurrentVersion(governance, version);
        MetricApprovalStatus decision =
                approved ? MetricApprovalStatus.APPROVED : MetricApprovalStatus.REJECTED;
        approval.setApprovalStatus(decision.name());
        approval.setAction(
                (approved ? MetricApprovalAction.APPROVE : MetricApprovalAction.REJECT).name());
        approval.setCommentText(request == null ? null : request.getComment());
        approval.setDecidedAt(new Date());
        approval.setDecidedBy(user.getName());
        approvalMapper.updateById(approval);
        version.setApprovalStatus(decision.name());
        versionMapper.updateById(version);
        updateGovernanceStatus(governance,
                approved ? MetricGovernanceStatus.APPROVED : MetricGovernanceStatus.REJECTED, user);
        return approval;
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricGovernanceDetail publish(Long metricId, Long versionId, String comment,
            User user) {
        MetricVersionDO version = requireVersion(metricId, versionId);
        if (!MetricApprovalStatus.APPROVED.name().equals(version.getApprovalStatus())) {
            throw new InvalidArgumentException("指标版本尚未审批通过");
        }
        MetricGovernanceDO governance = requireGovernance(metricId);
        requireCurrentVersion(governance, version);
        governance.setCurrentVersion(version.getVersionNo());
        governance.setEffectiveFrom(version.getEffectiveFrom());
        governance.setEffectiveTo(version.getEffectiveTo());
        updateGovernanceStatus(governance, MetricGovernanceStatus.PUBLISHED, user);
        metricService.batchPublish(Collections.singletonList(metricId), user);
        insertAudit(metricId, versionId, MetricApprovalAction.PUBLISH, comment, user);
        return detail(metricId, user);
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricGovernanceDetail deactivate(Long metricId, String comment, User user) {
        MetricGovernanceDO governance = requireGovernance(metricId);
        updateGovernanceStatus(governance, MetricGovernanceStatus.INACTIVE, user);
        metricService.batchUnPublish(Collections.singletonList(metricId), user);
        MetricVersionDO current = findVersion(metricId, governance.getCurrentVersion());
        insertAudit(metricId, current == null ? null : current.getId(),
                MetricApprovalAction.DEACTIVATE, comment, user);
        return detail(metricId, user);
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricVersionDO rollback(Long metricId, Integer versionNo, String comment, User user)
            throws Exception {
        MetricVersionDO target = findVersion(metricId, versionNo);
        if (target == null) {
            throw new InvalidArgumentException("目标指标版本不存在");
        }
        MetricVersionSnapshot snapshot =
                JsonUtil.toObject(target.getSnapshotJson(), MetricVersionSnapshot.class);
        MetricReq metricReq = toMetricReq(snapshot.getMetric());
        metricReq.setId(metricId);
        metricService.updateMetric(metricReq, user);

        MetricGovernanceDO governance = requireGovernance(metricId);
        governance.setOwnerDepartment(snapshot.getOwnerDepartment());
        governance.setSourceSystem(snapshot.getSourceSystem());
        governance.setBusinessDefinition(snapshot.getBusinessDefinition());
        governance.setEffectiveFrom(snapshot.getEffectiveFrom());
        governance.setEffectiveTo(snapshot.getEffectiveTo());
        governance.setGovernanceStatus(MetricGovernanceStatus.DRAFT.name());
        governance.setUpdatedAt(new Date());
        governance.setUpdatedBy(user.getName());
        governanceMapper.updateById(governance);
        MetricVersionDO restored = createVersionInternal(getMetric(metricId, user), governance,
                StringUtils.defaultIfBlank(comment, "回滚至版本 " + versionNo), user,
                MetricApprovalStatus.PENDING, true);
        insertAudit(metricId, restored.getId(), MetricApprovalAction.ROLLBACK,
                "sourceVersion=" + versionNo + "; " + StringUtils.defaultString(comment), user);
        return restored;
    }

    public MetricGovernanceDetail detail(Long metricId, User user) {
        MetricResp metric = getMetric(metricId, user);
        MetricGovernanceDetail detail = new MetricGovernanceDetail();
        detail.setMetric(metric);
        detail.setGovernance(findGovernance(metricId));
        detail.setVersions(listVersions(metricId));
        detail.setApprovals(listApprovals(metricId));
        detail.setOrganizationMappings(listMappings(metricId));
        return detail;
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricOrgMappingDO saveMapping(MetricOrgMappingReq request, User user) {
        getMetric(request.getMetricId(), user);
        validateMapping(request);
        MetricOrgMappingDO mapping = request.getId() == null ? findMapping(request)
                : mappingMapper.selectById(request.getId());
        boolean create = mapping == null;
        if (create) {
            mapping = new MetricOrgMappingDO();
            mapping.setCreatedAt(new Date());
            mapping.setCreatedBy(user.getName());
        }
        BeanUtils.copyProperties(request, mapping);
        mapping.setUpdatedAt(new Date());
        mapping.setUpdatedBy(user.getName());
        if (create) {
            mappingMapper.insert(mapping);
        } else {
            mappingMapper.updateById(mapping);
        }
        return mapping;
    }

    public List<MetricVersionDO> listVersions(Long metricId) {
        return versionMapper.selectList(new QueryWrapper<MetricVersionDO>().lambda()
                .eq(MetricVersionDO::getMetricId, metricId)
                .orderByDesc(MetricVersionDO::getVersionNo));
    }

    public List<MetricApprovalDO> listApprovals(Long metricId) {
        return approvalMapper.selectList(new QueryWrapper<MetricApprovalDO>().lambda()
                .eq(MetricApprovalDO::getMetricId, metricId)
                .orderByDesc(MetricApprovalDO::getCreatedAt));
    }

    public List<MetricOrgMappingDO> listMappings(Long metricId) {
        return mappingMapper.selectList(new QueryWrapper<MetricOrgMappingDO>().lambda()
                .eq(MetricOrgMappingDO::getMetricId, metricId)
                .orderByAsc(MetricOrgMappingDO::getOrganizationCode));
    }

    public List<MetricOrgMappingDO> listMappings(List<Long> metricIds) {
        if (metricIds.isEmpty()) {
            return new ArrayList<>();
        }
        return mappingMapper.selectList(new QueryWrapper<MetricOrgMappingDO>().lambda()
                .in(MetricOrgMappingDO::getMetricId, metricIds));
    }

    public List<MetricGovernanceDO> listGovernance(Long modelId, String status,
            String ownerDepartment, String sourceSystem) {
        List<Long> metricIds = metricService
                .getMetrics(new MetaFilter(Collections.singletonList(modelId))).stream()
                .map(MetricResp::getId).collect(java.util.stream.Collectors.toList());
        if (metricIds.isEmpty()) {
            return new ArrayList<>();
        }
        QueryWrapper<MetricGovernanceDO> query = new QueryWrapper<>();
        query.lambda().in(MetricGovernanceDO::getMetricId, metricIds);
        if (StringUtils.isNotBlank(status)) {
            query.lambda().eq(MetricGovernanceDO::getGovernanceStatus, status.toUpperCase());
        }
        if (StringUtils.isNotBlank(ownerDepartment)) {
            query.lambda().eq(MetricGovernanceDO::getOwnerDepartment, ownerDepartment);
        }
        if (StringUtils.isNotBlank(sourceSystem)) {
            query.lambda().eq(MetricGovernanceDO::getSourceSystem, sourceSystem);
        }
        query.lambda().orderByAsc(MetricGovernanceDO::getMetricId);
        return governanceMapper.selectList(query);
    }

    private synchronized MetricVersionDO createVersionInternal(MetricResp metric,
            MetricGovernanceDO governance, String summary, User user,
            MetricApprovalStatus approvalStatus, boolean unpublish) {
        int versionNo = nextVersion(metric.getId());
        MetricVersionDO version = new MetricVersionDO();
        version.setMetricId(metric.getId());
        version.setVersionNo(versionNo);
        version.setSnapshotJson(JsonUtil.toString(snapshot(metric, governance)));
        version.setChangeSummary(StringUtils.defaultIfBlank(summary, "指标版本 " + versionNo));
        version.setApprovalStatus(approvalStatus.name());
        version.setEffectiveFrom(governance.getEffectiveFrom());
        version.setEffectiveTo(governance.getEffectiveTo());
        version.setCreatedAt(new Date());
        version.setCreatedBy(user.getName());
        versionMapper.insert(version);
        governance.setCurrentVersion(versionNo);
        governance.setUpdatedAt(new Date());
        governance.setUpdatedBy(user.getName());
        governanceMapper.updateById(governance);
        if (unpublish) {
            metricService.batchUnPublish(Collections.singletonList(metric.getId()), user);
        }
        return version;
    }

    private MetricVersionSnapshot snapshot(MetricResp metric, MetricGovernanceDO governance) {
        MetricVersionSnapshot snapshot = new MetricVersionSnapshot();
        snapshot.setMetric(metric);
        snapshot.setOwnerDepartment(governance.getOwnerDepartment());
        snapshot.setSourceSystem(governance.getSourceSystem());
        snapshot.setBusinessDefinition(governance.getBusinessDefinition());
        snapshot.setEffectiveFrom(governance.getEffectiveFrom());
        snapshot.setEffectiveTo(governance.getEffectiveTo());
        return snapshot;
    }

    private MetricReq toMetricReq(MetricResp source) {
        MetricReq target = new MetricReq();
        target.setId(source.getId());
        target.setModelId(source.getModelId());
        target.setName(source.getName());
        target.setBizName(source.getBizName());
        target.setDescription(source.getDescription());
        target.setSensitiveLevel(source.getSensitiveLevel());
        target.setAlias(source.getAlias());
        target.setDataFormatType(source.getDataFormatType());
        target.setDataFormat(source.getDataFormat());
        target.setClassifications(source.getClassifications());
        target.setRelateDimension(source.getRelateDimension());
        target.setExt(source.getExt());
        target.setMetricDefineType(source.getMetricDefineType());
        if (MetricDefineType.FIELD.equals(source.getMetricDefineType())) {
            target.setMetricDefineByFieldParams(source.getMetricDefineByFieldParams());
        } else if (MetricDefineType.MEASURE.equals(source.getMetricDefineType())) {
            target.setMetricDefineByMeasureParams(source.getMetricDefineByMeasureParams());
        } else if (MetricDefineType.METRIC.equals(source.getMetricDefineType())) {
            target.setMetricDefineByMetricParams(source.getMetricDefineByMetricParams());
        }
        return target;
    }

    private void applyGovernance(MetricGovernanceDO target, MetricGovernanceReq source, User user) {
        target.setOwnerDepartment(source.getOwnerDepartment());
        target.setSourceSystem(source.getSourceSystem());
        target.setBusinessDefinition(source.getBusinessDefinition());
        target.setEffectiveFrom(source.getEffectiveFrom());
        target.setEffectiveTo(source.getEffectiveTo());
        target.setUpdatedAt(new Date());
        target.setUpdatedBy(user.getName());
    }

    private boolean governanceChanged(MetricGovernanceDO current, MetricGovernanceReq request) {
        return !Objects.equals(current.getOwnerDepartment(), request.getOwnerDepartment())
                || !Objects.equals(current.getSourceSystem(), request.getSourceSystem())
                || !Objects.equals(current.getBusinessDefinition(), request.getBusinessDefinition())
                || !Objects.equals(current.getEffectiveFrom(), request.getEffectiveFrom())
                || !Objects.equals(current.getEffectiveTo(), request.getEffectiveTo());
    }

    private String defaultSummary(MetricGovernanceReq request) {
        return StringUtils.defaultIfBlank(request.getChangeSummary(), "更新指标治理信息");
    }

    private MetricGovernanceDO findGovernance(Long metricId) {
        return governanceMapper.selectOne(new QueryWrapper<MetricGovernanceDO>().lambda()
                .eq(MetricGovernanceDO::getMetricId, metricId));
    }

    private MetricGovernanceDO requireGovernance(Long metricId) {
        MetricGovernanceDO governance = findGovernance(metricId);
        if (governance == null) {
            throw new InvalidArgumentException("指标尚未建立治理档案");
        }
        return governance;
    }

    private MetricVersionDO requireVersion(Long metricId, Long versionId) {
        MetricVersionDO version = versionMapper.selectById(versionId);
        if (version == null || !metricId.equals(version.getMetricId())) {
            throw new InvalidArgumentException("指标版本不存在");
        }
        return version;
    }

    private void requireCurrentVersion(MetricGovernanceDO governance, MetricVersionDO version) {
        if (!Objects.equals(governance.getCurrentVersion(), version.getVersionNo())) {
            throw new InvalidArgumentException("仅允许操作指标的当前版本");
        }
    }

    private MetricVersionDO findVersion(Long metricId, Integer versionNo) {
        if (versionNo == null) {
            return null;
        }
        return versionMapper.selectOne(new QueryWrapper<MetricVersionDO>().lambda()
                .eq(MetricVersionDO::getMetricId, metricId)
                .eq(MetricVersionDO::getVersionNo, versionNo));
    }

    private int nextVersion(Long metricId) {
        List<MetricVersionDO> versions =
                versionMapper.selectList(new QueryWrapper<MetricVersionDO>().lambda()
                        .eq(MetricVersionDO::getMetricId, metricId)
                        .orderByDesc(MetricVersionDO::getVersionNo).last("LIMIT 1"));
        return versions.isEmpty() ? 1 : versions.get(0).getVersionNo() + 1;
    }

    private boolean hasPendingApproval(Long metricId) {
        Long count = approvalMapper.selectCount(new QueryWrapper<MetricApprovalDO>().lambda()
                .eq(MetricApprovalDO::getMetricId, metricId)
                .eq(MetricApprovalDO::getApprovalStatus, MetricApprovalStatus.PENDING.name()));
        return count != null && count > 0;
    }

    private MetricOrgMappingDO findMapping(MetricOrgMappingReq request) {
        return mappingMapper.selectOne(new QueryWrapper<MetricOrgMappingDO>().lambda()
                .eq(MetricOrgMappingDO::getMetricId, request.getMetricId())
                .eq(MetricOrgMappingDO::getOrganizationCode, request.getOrganizationCode())
                .eq(MetricOrgMappingDO::getExternalMetricCode, request.getExternalMetricCode()));
    }

    private MetricResp getMetric(Long metricId, User user) {
        MetricResp metric = metricService.getMetric(metricId, user);
        if (metric == null) {
            throw new InvalidArgumentException("指标不存在");
        }
        return metric;
    }

    private void validateGovernanceReady(MetricGovernanceDO governance) {
        if (StringUtils.isAnyBlank(governance.getOwnerDepartment(), governance.getSourceSystem(),
                governance.getBusinessDefinition())) {
            throw new InvalidArgumentException("责任部门、来源系统和计算口径在提交审批前必须完整");
        }
        validateEffectiveRange(governance.getEffectiveFrom(), governance.getEffectiveTo());
    }

    private void validateMapping(MetricOrgMappingReq request) {
        if (request.getMetricId() == null || StringUtils.isAnyBlank(request.getOrganizationCode(),
                request.getExternalMetricCode(), request.getExternalMetricName())) {
            throw new InvalidArgumentException("指标、机构、外部指标编号和名称不能为空");
        }
        validateEffectiveRange(request.getEffectiveFrom(), request.getEffectiveTo());
    }

    private void validateEffectiveRange(Date from, Date to) {
        if (from != null && to != null && from.after(to)) {
            throw new InvalidArgumentException("生效开始时间不能晚于结束时间");
        }
    }

    private void updateGovernanceStatus(MetricGovernanceDO governance,
            MetricGovernanceStatus status, User user) {
        governance.setGovernanceStatus(status.name());
        governance.setUpdatedAt(new Date());
        governance.setUpdatedBy(user.getName());
        governanceMapper.updateById(governance);
    }

    private MetricApprovalDO approval(Long metricId, Long versionId, MetricApprovalAction action,
            MetricApprovalStatus status, String comment, User user) {
        MetricApprovalDO approval = new MetricApprovalDO();
        approval.setMetricId(metricId);
        approval.setVersionId(versionId);
        approval.setAction(action.name());
        approval.setApprovalStatus(status.name());
        approval.setCommentText(comment);
        approval.setCreatedAt(new Date());
        approval.setCreatedBy(user.getName());
        return approval;
    }

    private void insertAudit(Long metricId, Long versionId, MetricApprovalAction action,
            String comment, User user) {
        MetricApprovalDO audit =
                approval(metricId, versionId, action, MetricApprovalStatus.APPROVED, comment, user);
        audit.setDecidedAt(new Date());
        audit.setDecidedBy(user.getName());
        approvalMapper.insert(audit);
    }

    private String initialStatus(MetricResp metric) {
        return PublishEnum.PUBLISHED.getCode().equals(metric.getIsPublish())
                ? MetricGovernanceStatus.PUBLISHED.name()
                : MetricGovernanceStatus.DRAFT.name();
    }

    private MetricApprovalStatus initialApprovalStatus(MetricResp metric) {
        return PublishEnum.PUBLISHED.getCode().equals(metric.getIsPublish())
                ? MetricApprovalStatus.APPROVED
                : MetricApprovalStatus.PENDING;
    }
}
