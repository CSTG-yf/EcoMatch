package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import com.tencent.supersonic.headless.api.pojo.enums.ShareIdentityPolicy;
import com.tencent.supersonic.headless.api.pojo.enums.ShareStatus;
import com.tencent.supersonic.headless.api.pojo.request.ShareCreateReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.ShareAccessResp;
import com.tencent.supersonic.headless.api.pojo.response.ShareResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.ShareDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ShareMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.DashboardService;
import com.tencent.supersonic.headless.server.service.ShareService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ShareServiceImpl implements ShareService {

    static final int MAX_ALLOWED_USERS = 100;
    static final int MAX_ACCESS_COUNT = 100_000;
    static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private static final List<String> ORGANIZATION_ATTRIBUTE_KEYS =
            List.of("organizationId", "organizationCode", "orgId", "departmentId");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ShareMapper shareMapper;
    private final DashboardService dashboardService;
    private final AuditEventPublisher auditEventPublisher;
    private final ObjectMapper objectMapper;

    public ShareServiceImpl(ShareMapper shareMapper, DashboardService dashboardService,
            AuditEventPublisher auditEventPublisher, ObjectMapper objectMapper) {
        this.shareMapper = shareMapper;
        this.dashboardService = dashboardService;
        this.auditEventPublisher = auditEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ShareResp create(ShareCreateReq request, User user) {
        requireAuthenticated(user);
        validateCreate(request, user);
        DashboardResp dashboard = dashboardService.getManageable(request.getDashboardId(), user);
        if (dashboard.getStatus() != DashboardStatus.PUBLISHED) {
            throw new InvalidArgumentException("Only published dashboards can be shared");
        }
        String token = newToken();
        Date now = new Date();
        ShareDO share = new ShareDO();
        share.setShareId(UUID.randomUUID().toString());
        share.setTokenHash(hash(token));
        share.setDashboardId(dashboard.getId());
        share.setOwner(user.getName());
        share.setOrganizationId(organizationId(user));
        share.setIdentityPolicy(request.getIdentityPolicy().name());
        share.setAllowedUsers(writeAllowedUsers(normalizeUsers(request.getAllowedUsers())));
        share.setStatus(ShareStatus.ACTIVE.name());
        share.setMaxAccessCount(request.getMaxAccessCount());
        share.setAccessCount(0);
        share.setWatermarkEnabled(request.isWatermarkEnabled());
        share.setExpiresAt(request.getExpiresAt());
        share.setCreatedAt(now);
        share.setUpdatedAt(now);
        shareMapper.insert(share);
        publishRequired(share, user, AuditEventType.SHARE_CREATED, AuditOutcome.SUCCESS, null);
        ShareResp response = toResponse(share);
        response.setDashboardName(dashboard.getName());
        response.setToken(token);
        return response;
    }

    @Override
    public PageInfo<ShareResp> list(int pageNum, int pageSize, User user) {
        requireAuthenticated(user);
        validatePage(pageNum, pageSize);
        LambdaQueryWrapper<ShareDO> query = new LambdaQueryWrapper<ShareDO>();
        if (!user.isSuperAdmin()) {
            query.eq(ShareDO::getOwner, user.getName());
        }
        query.orderByDesc(ShareDO::getCreatedAt).orderByDesc(ShareDO::getId);
        PageInfo<ShareDO> page = PageHelper.startPage(pageNum, pageSize)
                .doSelectPageInfo(() -> shareMapper.selectList(query));
        PageInfo<ShareResp> response = new PageInfo<>();
        BeanUtils.copyProperties(page, response, "list");
        response.setList(page.getList().stream().map(this::refreshAndConvert).toList());
        return response;
    }

    @Override
    public ShareResp get(String shareId, User user) {
        return toResponse(requireManageable(shareId, user));
    }

    @Override
    @Transactional
    public void revoke(String shareId, User user) {
        ShareDO share = requireManageable(shareId, user);
        if (ShareStatus.REVOKED.name().equals(share.getStatus())) {
            return;
        }
        share.setStatus(ShareStatus.REVOKED.name());
        share.setRevokedAt(new Date());
        share.setUpdatedAt(new Date());
        shareMapper.updateById(share);
        publishRequired(share, user, AuditEventType.SHARE_REVOKED, AuditOutcome.SUCCESS, null);
    }

    @Override
    public ShareAccessResp access(String token, User user) {
        requireAuthenticated(user);
        if (StringUtils.isBlank(token) || token.length() < 40 || token.length() > 100) {
            denyAccess(null, user, "SHARE_TOKEN_INVALID");
        }
        ShareDO share = shareMapper.selectOne(
                new LambdaQueryWrapper<ShareDO>().eq(ShareDO::getTokenHash, hash(token)));
        if (share == null) {
            denyAccess(null, user, "SHARE_NOT_FOUND");
        }
        refreshExpiry(share);
        if (!ShareStatus.ACTIVE.name().equals(share.getStatus())) {
            denyAccess(share, user, "SHARE_INACTIVE");
        }
        enforceIdentityPolicy(share, user);
        DashboardResp dashboard;
        try {
            dashboard = dashboardService.getPublishedShared(share.getDashboardId(), user);
        } catch (RuntimeException e) {
            publishRequired(share, user, AuditEventType.SHARE_ACCESSED, AuditOutcome.DENIED,
                    "SHARE_TARGET_UNAVAILABLE");
            throw e;
        }
        Date now = new Date();
        if (shareMapper.incrementAccess(share.getId(), now) != 1) {
            denyAccess(share, user, "SHARE_ACCESS_LIMIT_REACHED");
        }
        share.setAccessCount(share.getAccessCount() + 1);
        publishRequired(share, user, AuditEventType.SHARE_ACCESSED, AuditOutcome.SUCCESS, null);
        ShareAccessResp response = new ShareAccessResp();
        response.setShareId(share.getShareId());
        response.setDashboard(dashboard);
        response.setAccessedAt(now);
        if (Boolean.TRUE.equals(share.getWatermarkEnabled())) {
            response.setWatermarkUser(user.getDisplayName());
            response.setWatermarkOrganization(organizationId(user));
        }
        return response;
    }

    private void validateCreate(ShareCreateReq request, User user) {
        if (request == null || request.getDashboardId() == null
                || request.getIdentityPolicy() == null || request.getExpiresAt() == null) {
            throw new InvalidArgumentException(
                    "Dashboard, identity policy and expiration are required");
        }
        Date now = new Date();
        if (!request.getExpiresAt().after(now)
                || request.getExpiresAt().toInstant().isAfter(now.toInstant().plus(MAX_LIFETIME))) {
            throw new InvalidArgumentException("Share expiration must be within the next 30 days");
        }
        if (request.getMaxAccessCount() != null && (request.getMaxAccessCount() < 1
                || request.getMaxAccessCount() > MAX_ACCESS_COUNT)) {
            throw new InvalidArgumentException(
                    "Share access limit must contain between 1 and 100000 accesses");
        }
        Set<String> allowedUsers = normalizeUsers(request.getAllowedUsers());
        if (request.getIdentityPolicy() == ShareIdentityPolicy.USERS && allowedUsers.isEmpty()) {
            throw new InvalidArgumentException("User-scoped shares require allowed users");
        }
        if (request.getIdentityPolicy() == ShareIdentityPolicy.ORGANIZATION
                && organizationId(user) == null) {
            throw new InvalidArgumentException(
                    "Organization shares require a trusted organization attribute");
        }
    }

    private void enforceIdentityPolicy(ShareDO share, User user) {
        ShareIdentityPolicy policy = ShareIdentityPolicy.valueOf(share.getIdentityPolicy());
        if (policy == ShareIdentityPolicy.ORGANIZATION
                && !Objects.equals(share.getOrganizationId(), organizationId(user))) {
            denyAccess(share, user, "SHARE_ORGANIZATION_FORBIDDEN");
        }
        if (policy == ShareIdentityPolicy.USERS
                && !readAllowedUsers(share.getAllowedUsers()).contains(user.getName())) {
            denyAccess(share, user, "SHARE_USER_FORBIDDEN");
        }
    }

    private ShareDO requireManageable(String shareId, User user) {
        requireAuthenticated(user);
        if (StringUtils.isBlank(shareId) || shareId.length() > 64) {
            throw new InvalidArgumentException("Share id is invalid");
        }
        ShareDO share = shareMapper
                .selectOne(new LambdaQueryWrapper<ShareDO>().eq(ShareDO::getShareId, shareId));
        if (share == null) {
            throw new InvalidArgumentException("Share does not exist");
        }
        if (!user.isSuperAdmin() && !Objects.equals(share.getOwner(), user.getName())) {
            throw new InvalidPermissionException("No permission to manage share");
        }
        refreshExpiry(share);
        return share;
    }

    private ShareResp refreshAndConvert(ShareDO share) {
        refreshExpiry(share);
        return toResponse(share);
    }

    private void refreshExpiry(ShareDO share) {
        if (ShareStatus.ACTIVE.name().equals(share.getStatus())
                && !share.getExpiresAt().after(new Date())) {
            share.setStatus(ShareStatus.EXPIRED.name());
            share.setUpdatedAt(new Date());
            shareMapper.updateById(share);
        }
    }

    private ShareResp toResponse(ShareDO share) {
        ShareResp response = new ShareResp();
        BeanUtils.copyProperties(share, response, "identityPolicy", "status", "allowedUsers");
        response.setIdentityPolicy(ShareIdentityPolicy.valueOf(share.getIdentityPolicy()));
        response.setStatus(ShareStatus.valueOf(share.getStatus()));
        response.setAllowedUsers(new ArrayList<>(readAllowedUsers(share.getAllowedUsers())));
        return response;
    }

    private Set<String> normalizeUsers(List<String> users) {
        Set<String> normalized = new LinkedHashSet<>();
        if (users == null) {
            return normalized;
        }
        if (users.size() > MAX_ALLOWED_USERS) {
            throw new InvalidArgumentException("Share cannot contain more than 100 users");
        }
        for (String user : users) {
            if (StringUtils.isBlank(user) || user.trim().length() > 100) {
                throw new InvalidArgumentException("Share contains an invalid user name");
            }
            normalized.add(user.trim());
        }
        return normalized;
    }

    private String writeAllowedUsers(Set<String> users) {
        try {
            return objectMapper.writeValueAsString(users);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize share users", e);
        }
    }

    private Set<String> readAllowedUsers(String users) {
        if (StringUtils.isBlank(users)) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(users, new TypeReference<LinkedHashSet<String>>() {});
        } catch (JsonProcessingException e) {
            throw new InvalidPermissionException("Share identity policy is invalid");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        return DigestUtils.sha256Hex(token);
    }

    private void denyAccess(ShareDO share, User user, String reasonCode) {
        publishRequired(share, user, AuditEventType.SHARE_ACCESSED, AuditOutcome.DENIED,
                reasonCode);
        throw new InvalidPermissionException("Share is not available");
    }

    private void publishRequired(ShareDO share, User user, AuditEventType eventType,
            AuditOutcome outcome, String reasonCode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (share != null) {
            metadata.put("shareId", share.getShareId());
            metadata.put("identityPolicy", share.getIdentityPolicy());
            metadata.put("status", share.getStatus());
            metadata.put("accessCount", share.getAccessCount());
            metadata.put("maxAccessCount", share.getMaxAccessCount());
        }
        auditEventPublisher.publishRequired(AuditEvent.builder().eventType(eventType)
                .outcome(outcome).reasonCode(reasonCode).resourceType("DASHBOARD")
                .resourceId(share == null ? null : String.valueOf(share.getDashboardId()))
                .metadata(metadata).build(), user);
    }

    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw new InvalidArgumentException(
                    "Share page number and page size must be within the allowed range");
        }
    }

    private void requireAuthenticated(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("User identity is required");
        }
    }

    private String organizationId(User user) {
        if (user == null || user.getAttributes() == null) {
            return null;
        }
        return ORGANIZATION_ATTRIBUTE_KEYS.stream().map(user.getAttributes()::get)
                .filter(StringUtils::isNotBlank).map(String::trim).findFirst().orElse(null);
    }
}
