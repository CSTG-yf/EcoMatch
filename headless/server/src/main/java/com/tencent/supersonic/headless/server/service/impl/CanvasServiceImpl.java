package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.request.CanvasReq;
import com.tencent.supersonic.headless.api.pojo.response.CanvasSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.CanvasDO;
import com.tencent.supersonic.headless.server.persistence.mapper.CanvasDOMapper;
import com.tencent.supersonic.headless.server.service.CanvasService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CanvasServiceImpl extends ServiceImpl<CanvasDOMapper, CanvasDO>
        implements CanvasService {

    @Autowired
    private ModelService modelService;

    @Autowired
    private DimensionService dimensionService;

    @Autowired
    private MetricService metricService;

    @Autowired
    private DomainService domainService;

    @Override
    public List<CanvasDO> getCanvasList(Long domainId, User user) {
        requireDomainAdmin(domainId, user);
        QueryWrapper<CanvasDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(CanvasDO::getDomainId, domainId);
        return list(queryWrapper);
    }

    @Override
    public List<CanvasSchemaResp> getCanvasSchema(Long domainId, User user) {
        requireDomainAdmin(domainId, user);
        List<CanvasSchemaResp> canvasSchemaResps = Lists.newArrayList();
        List<ModelResp> modelResps =
                modelService.getModelListWithAuth(user, domainId, AuthType.ADMIN);
        for (ModelResp modelResp : modelResps) {
            CanvasSchemaResp canvasSchemaResp = new CanvasSchemaResp();
            MetaFilter metaFilter = new MetaFilter();
            metaFilter.setModelIds(Lists.newArrayList(modelResp.getId()));
            List<MetricResp> metricResps = metricService.getMetrics(metaFilter);
            List<DimensionResp> dimensionResps = dimensionService.getDimensions(metaFilter);
            canvasSchemaResp.setModel(modelResp);
            canvasSchemaResp.setDimensions(dimensionResps);
            canvasSchemaResp.setMetrics(metricResps);
            canvasSchemaResp.setDomainId(domainId);
            canvasSchemaResps.add(canvasSchemaResp);
        }
        return canvasSchemaResps;
    }

    @Override
    public CanvasDO createOrUpdateCanvas(CanvasReq canvasReq, User user) {
        if (canvasReq == null || canvasReq.getDomainId() == null) {
            throw new InvalidArgumentException("Canvas domain is required");
        }
        if (canvasReq.getId() == null) {
            requireDomainAdmin(canvasReq.getDomainId(), user);
            Date now = new Date();
            CanvasDO viewInfoDO = new CanvasDO();
            viewInfoDO.setDomainId(canvasReq.getDomainId());
            viewInfoDO.setType(canvasReq.getType());
            viewInfoDO.setConfig(canvasReq.getConfig());
            viewInfoDO.setCreatedBy(user.getName());
            viewInfoDO.setCreatedAt(now);
            viewInfoDO.setUpdatedBy(user.getName());
            viewInfoDO.setUpdatedAt(now);
            save(viewInfoDO);
            return viewInfoDO;
        }
        Long id = canvasReq.getId();
        CanvasDO viewInfoDO = getById(id);
        if (viewInfoDO == null) {
            throw new InvalidArgumentException("Canvas does not exist");
        }
        requireDomainAdmin(viewInfoDO.getDomainId(), user);
        if (!viewInfoDO.getDomainId().equals(canvasReq.getDomainId())) {
            throw new InvalidArgumentException("Canvas domain cannot be changed");
        }
        viewInfoDO.setType(canvasReq.getType());
        viewInfoDO.setConfig(canvasReq.getConfig());
        viewInfoDO.setUpdatedBy(user.getName());
        viewInfoDO.setUpdatedAt(new Date());
        updateById(viewInfoDO);
        return viewInfoDO;
    }

    @Override
    public void deleteCanvas(Long id, User user) {
        CanvasDO canvas = getById(id);
        if (canvas == null) {
            throw new InvalidArgumentException("Canvas does not exist");
        }
        requireDomainAdmin(canvas.getDomainId(), user);
        removeById(id);
    }

    private void requireDomainAdmin(Long domainId, User user) {
        if (domainId == null) {
            throw new InvalidArgumentException("Canvas domain is required");
        }
        if (user == null) {
            throw new InvalidPermissionException("User identity is required");
        }
        if (user.isSuperAdmin()) {
            if (domainService.getDomain(domainId) == null) {
                throw new InvalidArgumentException("Canvas domain does not exist");
            }
            return;
        }
        Set<DomainResp> domains = domainService.getDomainAuthSet(user, AuthType.ADMIN);
        Set<Long> domainIds = domains == null ? Set.of()
                : domains.stream().map(domain -> domain.getId())
                        .collect(Collectors.toCollection(HashSet::new));
        if (!domainIds.contains(domainId)) {
            throw new InvalidPermissionException("No permission to manage canvas");
        }
    }
}
