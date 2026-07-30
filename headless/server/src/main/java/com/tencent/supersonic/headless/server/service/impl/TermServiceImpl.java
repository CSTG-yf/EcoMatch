package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.TermReq;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.TermDO;
import com.tencent.supersonic.headless.server.persistence.mapper.TermMapper;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.TermService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TermServiceImpl extends ServiceImpl<TermMapper, TermDO> implements TermService {

    private final DomainService domainService;

    public TermServiceImpl(DomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void saveOrUpdate(TermReq termReq, User user) {
        if (termReq == null || termReq.getDomainId() == null || termReq.getDomainId() <= 0) {
            throw new InvalidArgumentException("Term domain id must be positive");
        }
        TermDO termSetDO;
        if (termReq.getId() != null) {
            termSetDO = getRequiredTerm(termReq.getId());
            requireDomainAdmin(termSetDO.getDomainId(), user);
        } else {
            termSetDO = new TermDO();
        }
        requireDomainAdmin(termReq.getDomainId(), user);
        String createdBy = termSetDO.getCreatedBy();
        Date createdAt = termSetDO.getCreatedAt();
        if (termReq.getId() == null) {
            termReq.createdBy(user.getName());
        }
        termReq.updatedBy(user.getName());
        convert(termReq, termSetDO);
        if (termReq.getId() != null) {
            termSetDO.setCreatedBy(createdBy);
            termSetDO.setCreatedAt(createdAt);
        }
        saveOrUpdate(termSetDO);
    }

    @Override
    public void delete(Long id, User user) {
        TermDO term = getRequiredTerm(id);
        requireDomainAdmin(term.getDomainId(), user);
        removeById(id);
    }

    @Override
    public void deleteBatch(MetaBatchReq metaBatchReq, User user) {
        if (metaBatchReq == null || CollectionUtils.isEmpty(metaBatchReq.getIds())) {
            throw new InvalidArgumentException("Term ids must not be empty");
        }
        Set<Long> ids = new LinkedHashSet<>(metaBatchReq.getIds());
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidArgumentException("Term ids must be positive");
        }
        List<TermDO> terms = listByIds(ids);
        if (terms.size() != ids.size()) {
            throw new InvalidArgumentException("One or more terms do not exist");
        }
        terms.forEach(term -> requireDomainAdmin(term.getDomainId(), user));
        removeBatchByIds(ids);
    }

    @Override
    public List<TermResp> getTerms(Long domainId, String queryKey, User user) {
        requireDomainAccess(domainId, user, AuthType.VIEWER);
        QueryWrapper<TermDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(TermDO::getDomainId, domainId);
        if (StringUtils.isNotBlank(queryKey)) {
            queryWrapper.lambda().and(i -> i.like(TermDO::getName, queryKey).or()
                    .like(TermDO::getDescription, queryKey).or().like(TermDO::getAlias, queryKey));
        }
        List<TermDO> termDOS = list(queryWrapper);
        return termDOS.stream().map(this::convert).collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<TermResp>> getTermSets(Set<Long> domainIds) {
        if (CollectionUtils.isEmpty(domainIds)) {
            return new HashMap<>();
        }
        QueryWrapper<TermDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().in(TermDO::getDomainId, domainIds);
        List<TermDO> list = list(queryWrapper);
        return list.stream().map(this::convert)
                .collect(Collectors.groupingBy(TermResp::getDomainId));
    }

    private TermResp convert(TermDO termDO) {
        TermResp termSetResp = new TermResp();
        BeanMapper.mapper(termDO, termSetResp);
        termSetResp.setAlias(JsonUtil.toList(termDO.getAlias(), String.class));
        termSetResp.setRelatedMetrics(JsonUtil.toList(termDO.getRelatedMetrics(), Long.class));
        termSetResp.setRelateDimensions(JsonUtil.toList(termDO.getRelatedDimensions(), Long.class));
        return termSetResp;
    }

    private void convert(TermReq termReq, TermDO termDO) {
        BeanMapper.mapper(termReq, termDO);
        termDO.setAlias(JsonUtil.toString(termReq.getAlias()));
        termDO.setRelatedDimensions(JsonUtil.toString(termReq.getRelateDimensions()));
        termDO.setRelatedMetrics(JsonUtil.toString(termReq.getRelatedMetrics()));
    }

    private TermDO getRequiredTerm(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidArgumentException("Term id must be positive");
        }
        TermDO term = getById(id);
        if (term == null) {
            throw new InvalidArgumentException("Term does not exist");
        }
        return term;
    }

    private void requireDomainAdmin(Long domainId, User user) {
        requireDomainAccess(domainId, user, AuthType.ADMIN);
    }

    private void requireDomainAccess(Long domainId, User user, AuthType authType) {
        if (domainId == null || domainId <= 0) {
            throw new InvalidArgumentException("Term domain id must be positive");
        }
        if (user == null || StringUtils.isBlank(user.getName())
                || User.getVisitUser().getName().equals(user.getName())) {
            throw new InvalidPermissionException("No permission to manage term domain");
        }
        if (user.isSuperAdmin()) {
            return;
        }
        boolean allowed = domainService.getDomainAuthSet(user, authType).stream()
                .map(DomainResp::getId).anyMatch(domainId::equals);
        if (!allowed) {
            throw new InvalidPermissionException("No permission to manage term domain");
        }
    }
}
