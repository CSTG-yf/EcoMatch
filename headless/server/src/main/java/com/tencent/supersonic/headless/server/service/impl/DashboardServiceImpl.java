package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardAccessScope;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCopyReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCreateReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardUpdateReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.DashboardDO;
import com.tencent.supersonic.headless.server.persistence.mapper.DashboardMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.DashboardService;
import com.tencent.supersonic.headless.server.service.DomainService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 1_000;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final int MAX_CONFIG_DEPTH = 12;
    private static final int MAX_CONFIG_NODES = 5_000;
    private static final int MAX_COMPONENTS = 100;
    private static final Set<String> SENSITIVE_CONFIG_KEYS = Set.of("password", "token", "secret",
            "apikey", "accesstoken", "refreshtoken", "sql", "rawsql", "credential");
    private static final List<String> ORGANIZATION_ATTRIBUTE_KEYS =
            List.of("organizationId", "organizationCode", "orgId", "departmentId");

    private final DashboardMapper dashboardMapper;
    private final DomainService domainService;
    private final AuditEventPublisher auditEventPublisher;
    private final ObjectMapper objectMapper;

    public DashboardServiceImpl(DashboardMapper dashboardMapper, DomainService domainService,
            AuditEventPublisher auditEventPublisher, ObjectMapper objectMapper) {
        this.dashboardMapper = dashboardMapper;
        this.domainService = domainService;
        this.auditEventPublisher = auditEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageInfo<DashboardResp> list(Long domainId, DashboardStatus status, int pageNum,
            int pageSize, User user) {
        requireDomainAccess(domainId, user, AuthType.VIEWER);
        validatePage(pageNum, pageSize);
        LambdaQueryWrapper<DashboardDO> query =
                new LambdaQueryWrapper<DashboardDO>().eq(DashboardDO::getDomainId, domainId);
        if (status != null) {
            query.eq(DashboardDO::getStatus, status.name());
        }
        if (!user.isSuperAdmin() && !hasDomainAccess(domainId, user, AuthType.ADMIN)) {
            String organizationId = organizationId(user);
            query.and(visible -> {
                visible.eq(DashboardDO::getOwner, user.getName()).or(domain -> domain
                        .eq(DashboardDO::getStatus, DashboardStatus.PUBLISHED.name())
                        .eq(DashboardDO::getAccessScope, DashboardAccessScope.DOMAIN.name()));
                if (organizationId != null) {
                    visible.or(organization -> organization
                            .eq(DashboardDO::getStatus, DashboardStatus.PUBLISHED.name())
                            .eq(DashboardDO::getAccessScope,
                                    DashboardAccessScope.ORGANIZATION.name())
                            .eq(DashboardDO::getOrganizationId, organizationId));
                }
            });
        }
        query.orderByDesc(DashboardDO::getUpdatedAt).orderByDesc(DashboardDO::getId);
        PageInfo<DashboardDO> dataPage = PageHelper.startPage(pageNum, pageSize)
                .doSelectPageInfo(() -> dashboardMapper.selectList(query));
        PageInfo<DashboardResp> responsePage = new PageInfo<>();
        BeanUtils.copyProperties(dataPage, responsePage, "list");
        responsePage.setList(dataPage.getList().stream().map(this::toSummary).toList());
        return responsePage;
    }

    @Override
    public DashboardResp get(Long id, User user) {
        DashboardDO dashboard = requireExisting(id);
        requireDomainAccess(dashboard.getDomainId(), user, AuthType.VIEWER);
        if (!canRead(dashboard, user)) {
            deny(user, dashboard, "DASHBOARD_READ_FORBIDDEN");
        }
        audit(AuditEventType.DASHBOARD_ACCESSED, dashboard, user);
        return toResponse(dashboard);
    }

    @Override
    public DashboardResp getManageable(Long id, User user) {
        return toResponse(requireEditable(id, user));
    }

    @Override
    public DashboardResp getPublishedShared(Long id, User user) {
        DashboardDO dashboard = requireExisting(id);
        requireDomainAccess(dashboard.getDomainId(), user, AuthType.VIEWER);
        if (!DashboardStatus.PUBLISHED.name().equals(dashboard.getStatus())) {
            deny(user, dashboard, "DASHBOARD_SHARE_TARGET_UNAVAILABLE");
        }
        audit(AuditEventType.DASHBOARD_ACCESSED, dashboard, user,
                metadata -> metadata.put("entryPoint", "share"));
        return toResponse(dashboard);
    }

    @Override
    public DashboardResp create(DashboardCreateReq request, User user) {
        requireAuthenticated(user);
        if (request == null) {
            throw new InvalidArgumentException("Dashboard request is required");
        }
        requireDomainAccess(request.getDomainId(), user, AuthType.VIEWER);
        validateName(request.getName());
        validateDescription(request.getDescription());
        validateConfig(request.getConfig());
        DashboardAccessScope accessScope =
                defaultScope(request.getAccessScope(), DashboardAccessScope.PRIVATE);
        Date now = new Date();
        DashboardDO dashboard = new DashboardDO();
        dashboard.setDomainId(request.getDomainId());
        dashboard.setName(request.getName().trim());
        dashboard.setDescription(StringUtils.trimToNull(request.getDescription()));
        dashboard.setStatus(DashboardStatus.DRAFT.name());
        dashboard.setAccessScope(accessScope.name());
        dashboard.setOwner(user.getName());
        dashboard.setOrganizationId(organizationForScope(accessScope, user));
        dashboard.setConfig(request.getConfig());
        dashboard.setVersion(0);
        dashboard.setCreatedAt(now);
        dashboard.setCreatedBy(user.getName());
        dashboard.setUpdatedAt(now);
        dashboard.setUpdatedBy(user.getName());
        dashboardMapper.insert(dashboard);
        audit(AuditEventType.DASHBOARD_CREATED, dashboard, user);
        return toResponse(dashboard);
    }

    @Override
    public DashboardResp update(Long id, DashboardUpdateReq request, User user) {
        if (request == null) {
            throw new InvalidArgumentException("Dashboard update request is required");
        }
        DashboardDO dashboard = requireEditable(id, user);
        requireVersion(dashboard, request.getVersion());
        if (DashboardStatus.DISABLED.name().equals(dashboard.getStatus())) {
            throw new InvalidArgumentException("Disabled dashboards cannot be updated");
        }
        validateName(request.getName());
        validateDescription(request.getDescription());
        validateConfig(request.getConfig());
        DashboardAccessScope accessScope =
                defaultScope(request.getAccessScope(), DashboardAccessScope.PRIVATE);
        dashboard.setName(request.getName().trim());
        dashboard.setDescription(StringUtils.trimToNull(request.getDescription()));
        dashboard.setAccessScope(accessScope.name());
        dashboard.setOrganizationId(organizationForScope(accessScope, user));
        dashboard.setConfig(request.getConfig());
        touch(dashboard, user);
        updateWithVersion(dashboard);
        audit(AuditEventType.DASHBOARD_UPDATED, dashboard, user);
        return toResponse(dashboard);
    }

    @Override
    public DashboardResp copy(Long id, DashboardCopyReq request, User user) {
        DashboardDO source = requireExisting(id);
        requireDomainAccess(source.getDomainId(), user, AuthType.VIEWER);
        if (!canRead(source, user)) {
            deny(user, source, "DASHBOARD_COPY_FORBIDDEN");
        }
        DashboardCreateReq create = new DashboardCreateReq();
        create.setDomainId(source.getDomainId());
        String copyName = request == null ? null : request.getName();
        create.setName(StringUtils.isBlank(copyName) ? source.getName() + " copy" : copyName);
        create.setDescription(source.getDescription());
        create.setAccessScope(DashboardAccessScope.PRIVATE);
        create.setConfig(source.getConfig());
        DashboardResp copied = create(create, user);
        audit(AuditEventType.DASHBOARD_COPIED, responseToDataObject(copied), user,
                metadata -> metadata.put("sourceId", source.getId()));
        return copied;
    }

    @Override
    public DashboardResp publish(Long id, Integer version, User user) {
        DashboardDO dashboard = requireEditable(id, user);
        requireVersion(dashboard, version);
        if (DashboardStatus.DISABLED.name().equals(dashboard.getStatus())) {
            throw new InvalidArgumentException("Disabled dashboards cannot be published");
        }
        dashboard.setStatus(DashboardStatus.PUBLISHED.name());
        dashboard.setPublishedAt(new Date());
        dashboard.setDisabledAt(null);
        touch(dashboard, user);
        updateWithVersion(dashboard);
        audit(AuditEventType.DASHBOARD_PUBLISHED, dashboard, user);
        return toResponse(dashboard);
    }

    @Override
    public DashboardResp disable(Long id, Integer version, User user) {
        DashboardDO dashboard = requireEditable(id, user);
        requireVersion(dashboard, version);
        if (!DashboardStatus.PUBLISHED.name().equals(dashboard.getStatus())) {
            throw new InvalidArgumentException("Only published dashboards can be disabled");
        }
        dashboard.setStatus(DashboardStatus.DISABLED.name());
        dashboard.setDisabledAt(new Date());
        touch(dashboard, user);
        updateWithVersion(dashboard);
        audit(AuditEventType.DASHBOARD_DISABLED, dashboard, user);
        return toResponse(dashboard);
    }

    @Override
    public void delete(Long id, User user) {
        DashboardDO dashboard = requireEditable(id, user);
        int removed = dashboardMapper.deleteById(id);
        if (removed != 1) {
            throw new InvalidArgumentException("Dashboard was already removed");
        }
        audit(AuditEventType.DASHBOARD_DELETED, dashboard, user);
    }

    private DashboardDO requireEditable(Long id, User user) {
        DashboardDO dashboard = requireExisting(id);
        requireDomainAccess(dashboard.getDomainId(), user, AuthType.VIEWER);
        if (!isOwner(dashboard, user)
                && !hasDomainAccess(dashboard.getDomainId(), user, AuthType.ADMIN)) {
            deny(user, dashboard, "DASHBOARD_WRITE_FORBIDDEN");
        }
        return dashboard;
    }

    private DashboardDO requireExisting(Long id) {
        if (id == null) {
            throw new InvalidArgumentException("Dashboard id is required");
        }
        DashboardDO dashboard = dashboardMapper.selectById(id);
        if (dashboard == null) {
            throw new InvalidArgumentException("Dashboard does not exist");
        }
        return dashboard;
    }

    private void requireDomainAccess(Long domainId, User user, AuthType authType) {
        requireAuthenticated(user);
        if (domainId == null) {
            throw new InvalidArgumentException("Dashboard domain is required");
        }
        if (!hasDomainAccess(domainId, user, authType)) {
            deny(user, null, "DASHBOARD_DOMAIN_FORBIDDEN");
        }
    }

    private boolean hasDomainAccess(Long domainId, User user, AuthType authType) {
        if (user != null && user.isSuperAdmin()) {
            return domainService.getDomain(domainId) != null;
        }
        Set<DomainResp> domains = domainService.getDomainAuthSet(user, authType);
        return domains != null
                && domains.stream().map(DomainResp::getId).anyMatch(domainId::equals);
    }

    private boolean canRead(DashboardDO dashboard, User user) {
        if (user.isSuperAdmin() || isOwner(dashboard, user)) {
            return true;
        }
        if (!DashboardStatus.PUBLISHED.name().equals(dashboard.getStatus())) {
            return false;
        }
        if (DashboardAccessScope.DOMAIN.name().equals(dashboard.getAccessScope())) {
            return true;
        }
        return DashboardAccessScope.ORGANIZATION.name().equals(dashboard.getAccessScope())
                && Objects.equals(dashboard.getOrganizationId(), organizationId(user));
    }

    private boolean isOwner(DashboardDO dashboard, User user) {
        return user != null && Objects.equals(dashboard.getOwner(), user.getName());
    }

    private void requireAuthenticated(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("User identity is required");
        }
    }

    private void requireVersion(DashboardDO dashboard, Integer requestedVersion) {
        if (requestedVersion == null) {
            throw new InvalidArgumentException("Dashboard version is required");
        }
        if (!Objects.equals(dashboard.getVersion(), requestedVersion)) {
            throw new InvalidArgumentException("Dashboard version conflict");
        }
    }

    private void updateWithVersion(DashboardDO dashboard) {
        if (dashboardMapper.updateWithVersion(dashboard) != 1) {
            throw new InvalidArgumentException("Dashboard version conflict");
        }
        dashboard.setVersion(dashboard.getVersion() + 1);
    }

    private void touch(DashboardDO dashboard, User user) {
        dashboard.setUpdatedAt(new Date());
        dashboard.setUpdatedBy(user.getName());
    }

    private void validateName(String name) {
        if (StringUtils.isBlank(name) || name.trim().length() > MAX_NAME_LENGTH) {
            throw new InvalidArgumentException(
                    "Dashboard name must contain between 1 and 120 characters");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidArgumentException(
                    "Dashboard description cannot exceed 1000 characters");
        }
    }

    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new InvalidArgumentException("Dashboard page number must be positive");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new InvalidArgumentException(
                    "Dashboard page size must contain between 1 and 100 items");
        }
    }

    private void validateConfig(String config) {
        if (StringUtils.isBlank(config)) {
            throw new InvalidArgumentException("Dashboard config is required");
        }
        if (config.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new InvalidArgumentException("Dashboard config exceeds the size limit");
        }
        try {
            JsonNode root = objectMapper.readTree(config);
            if (root == null || !root.isObject()) {
                throw new InvalidArgumentException("Dashboard config must be a JSON object");
            }
            JsonNode components = root.get("components");
            if (components != null
                    && (!components.isArray() || components.size() > MAX_COMPONENTS)) {
                throw new InvalidArgumentException(
                        "Dashboard components must be an array with at most 100 items");
            }
            validateNode(root, 0, new int[] {0});
        } catch (JsonProcessingException e) {
            throw new InvalidArgumentException("Dashboard config is not valid JSON");
        }
    }

    private void validateNode(JsonNode node, int depth, int[] count) {
        if (depth > MAX_CONFIG_DEPTH || ++count[0] > MAX_CONFIG_NODES) {
            throw new InvalidArgumentException("Dashboard config exceeds the structure limit");
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().replace("_", "").replace("-", "").toLowerCase();
                if (isSensitiveConfigKey(normalized)) {
                    throw new InvalidArgumentException(
                            "Dashboard config contains a forbidden sensitive field");
                }
                validateNode(field.getValue(), depth + 1, count);
            }
        } else if (node.isArray()) {
            node.forEach(child -> validateNode(child, depth + 1, count));
        } else if (node.isTextual() && node.textValue().length() > 16_384) {
            throw new InvalidArgumentException("Dashboard config text value is too long");
        }
    }

    private DashboardAccessScope defaultScope(DashboardAccessScope requested,
            DashboardAccessScope fallback) {
        return requested == null ? fallback : requested;
    }

    private boolean isSensitiveConfigKey(String normalized) {
        return SENSITIVE_CONFIG_KEYS.stream().anyMatch(
                forbidden -> normalized.equals(forbidden) || normalized.endsWith(forbidden));
    }

    private String organizationForScope(DashboardAccessScope accessScope, User user) {
        if (accessScope != DashboardAccessScope.ORGANIZATION) {
            return null;
        }
        String organizationId = organizationId(user);
        if (organizationId == null) {
            throw new InvalidArgumentException(
                    "Organization-scoped dashboards require a trusted organization attribute");
        }
        return organizationId;
    }

    private String organizationId(User user) {
        if (user == null || user.getAttributes() == null) {
            return null;
        }
        return ORGANIZATION_ATTRIBUTE_KEYS.stream().map(user.getAttributes()::get)
                .filter(StringUtils::isNotBlank).map(String::trim).findFirst().orElse(null);
    }

    private void deny(User user, DashboardDO dashboard, String reasonCode) {
        auditEventPublisher.publishBestEffort(AuditEvent.builder()
                .eventType(AuditEventType.OBJECT_ACCESS_DENIED).resourceType("DASHBOARD")
                .resourceId(dashboard == null ? null : String.valueOf(dashboard.getId()))
                .outcome(AuditOutcome.DENIED).reasonCode(reasonCode).build(), user);
        throw new InvalidPermissionException("No permission to access dashboard");
    }

    private void audit(AuditEventType eventType, DashboardDO dashboard, User user) {
        audit(eventType, dashboard, user, metadata -> {
        });
    }

    private void audit(AuditEventType eventType, DashboardDO dashboard, User user,
            Consumer<Map<String, Object>> customizer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("domainId", dashboard.getDomainId());
        metadata.put("status", dashboard.getStatus());
        metadata.put("accessScope", dashboard.getAccessScope());
        metadata.put("version", dashboard.getVersion());
        customizer.accept(metadata);
        auditEventPublisher.publishBestEffort(AuditEvent.builder().eventType(eventType)
                .resourceType("DASHBOARD").resourceId(String.valueOf(dashboard.getId()))
                .outcome(AuditOutcome.SUCCESS).metadata(metadata).build(), user);
    }

    private DashboardResp toResponse(DashboardDO dashboard) {
        DashboardResp response = new DashboardResp();
        BeanUtils.copyProperties(dashboard, response, "status", "accessScope");
        response.setStatus(DashboardStatus.valueOf(dashboard.getStatus()));
        response.setAccessScope(DashboardAccessScope.valueOf(dashboard.getAccessScope()));
        return response;
    }

    private DashboardResp toSummary(DashboardDO dashboard) {
        DashboardResp response = toResponse(dashboard);
        response.setConfig(null);
        return response;
    }

    private DashboardDO responseToDataObject(DashboardResp response) {
        DashboardDO dashboard = new DashboardDO();
        BeanUtils.copyProperties(response, dashboard, "status", "accessScope");
        dashboard.setStatus(response.getStatus().name());
        dashboard.setAccessScope(response.getAccessScope().name());
        return dashboard;
    }
}
