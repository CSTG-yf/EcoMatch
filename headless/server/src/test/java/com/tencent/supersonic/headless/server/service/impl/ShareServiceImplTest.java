package com.tencent.supersonic.headless.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareServiceImplTest {

    private ShareMapper mapper;
    private DashboardService dashboardService;
    private AuditEventPublisher auditPublisher;
    private ShareServiceImpl service;
    private ShareDO persisted;

    @BeforeEach
    void setUp() {
        mapper = mock(ShareMapper.class);
        dashboardService = mock(DashboardService.class);
        auditPublisher = mock(AuditEventPublisher.class);
        service =
                new ShareServiceImpl(mapper, dashboardService, auditPublisher, new ObjectMapper());
        when(mapper.insert(any(ShareDO.class))).thenAnswer(invocation -> {
            persisted = invocation.getArgument(0);
            persisted.setId(1L);
            return 1;
        });
        when(mapper.updateById(any(ShareDO.class))).thenReturn(1);
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(12L);
        dashboard.setName("Risk dashboard");
        dashboard.setStatus(DashboardStatus.PUBLISHED);
        when(dashboardService.getManageable(any(), any())).thenReturn(dashboard);
        when(dashboardService.getPublishedShared(any(), any())).thenReturn(dashboard);
    }

    @Test
    void createReturnsRawTokenOnceAndPersistsOnlyHash() {
        ShareResp response =
                service.create(request(ShareIdentityPolicy.AUTHENTICATED), user("alice", "org-a"));

        assertNotNull(response.getToken());
        assertTrue(response.getToken().length() >= 40);
        assertEquals(64, persisted.getTokenHash().length());
        assertNotEquals(response.getToken(), persisted.getTokenHash());
        assertFalse(persisted.toString().contains(response.getToken()));
        verify(auditPublisher).publishRequired(org.mockito.ArgumentMatchers
                .argThat(event -> event.getEventType() == AuditEventType.SHARE_CREATED), any());
    }

    @Test
    void accessChecksPolicyAtomicallyAndReturnsWatermark() {
        ShareResp created =
                service.create(request(ShareIdentityPolicy.ORGANIZATION), user("alice", "org-a"));
        when(mapper.selectOne(any())).thenReturn(persisted);
        when(mapper.incrementAccess(any(), any())).thenReturn(1);

        ShareAccessResp response = service.access(created.getToken(), user("bob", "org-a"));

        assertEquals("bob", response.getWatermarkUser());
        assertEquals("org-a", response.getWatermarkOrganization());
        assertEquals(1, persisted.getAccessCount());
        verify(dashboardService).getPublishedShared(12L, user("bob", "org-a"));
    }

    @Test
    void crossOrganizationAccessFailsClosedAndIsAudited() {
        ShareResp created =
                service.create(request(ShareIdentityPolicy.ORGANIZATION), user("alice", "org-a"));
        when(mapper.selectOne(any())).thenReturn(persisted);

        assertThrows(InvalidPermissionException.class,
                () -> service.access(created.getToken(), user("bob", "org-b")));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, org.mockito.Mockito.atLeast(2)).publishRequired(event.capture(),
                any());
        assertTrue(event.getAllValues().stream()
                .anyMatch(value -> value.getEventType() == AuditEventType.SHARE_ACCESSED
                        && value.getOutcome() == AuditOutcome.DENIED));
    }

    @Test
    void accessLimitRaceFailsClosed() {
        ShareResp created =
                service.create(request(ShareIdentityPolicy.AUTHENTICATED), user("alice", "org-a"));
        when(mapper.selectOne(any())).thenReturn(persisted);
        when(mapper.incrementAccess(any(), any())).thenReturn(0);

        assertThrows(InvalidPermissionException.class,
                () -> service.access(created.getToken(), user("bob", "org-b")));
    }

    @Test
    void revokedShareCannotBeAccessed() {
        ShareResp created =
                service.create(request(ShareIdentityPolicy.AUTHENTICATED), user("alice", "org-a"));
        when(mapper.selectOne(any())).thenReturn(persisted);
        service.revoke(persisted.getShareId(), user("alice", "org-a"));

        assertEquals(ShareStatus.REVOKED.name(), persisted.getStatus());
        assertNotNull(persisted.getRevokedAt());
        assertThrows(InvalidPermissionException.class,
                () -> service.access(created.getToken(), user("alice", "org-a")));
    }

    @Test
    void onlyPublishedDashboardCanBeShared() {
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(12L);
        dashboard.setStatus(DashboardStatus.DRAFT);
        when(dashboardService.getManageable(any(), any())).thenReturn(dashboard);

        assertThrows(InvalidArgumentException.class, () -> service
                .create(request(ShareIdentityPolicy.AUTHENTICATED), user("alice", "org-a")));
    }

    @Test
    void createResponseDoesNotExposeTokenOnSubsequentReads() {
        service.create(request(ShareIdentityPolicy.USERS), user("alice", "org-a"));
        when(mapper.selectOne(any())).thenReturn(persisted);

        ShareResp response = service.get(persisted.getShareId(), user("alice", "org-a"));

        assertNull(response.getToken());
        assertEquals(List.of("bob"), response.getAllowedUsers());
    }

    @Test
    void expiredShareFailsClosed() {
        ShareResp created =
                service.create(request(ShareIdentityPolicy.AUTHENTICATED), user("alice", "org-a"));
        persisted.setExpiresAt(new Date(System.currentTimeMillis() - 1));
        when(mapper.selectOne(any())).thenReturn(persisted);

        assertThrows(InvalidPermissionException.class,
                () -> service.access(created.getToken(), user("bob", "org-a")));
        assertEquals(ShareStatus.EXPIRED.name(), persisted.getStatus());
    }

    @Test
    void userPolicyRejectsUsersOutsideAllowList() {
        ShareResp created =
                service.create(request(ShareIdentityPolicy.USERS), user("alice", "org-a"));
        when(mapper.selectOne(any())).thenReturn(persisted);

        assertThrows(InvalidPermissionException.class,
                () -> service.access(created.getToken(), user("mallory", "org-a")));
    }

    @Test
    void expirationBeyondThirtyDaysIsRejected() {
        ShareCreateReq request = request(ShareIdentityPolicy.AUTHENTICATED);
        request.setExpiresAt(
                new Date(System.currentTimeMillis() + java.time.Duration.ofDays(31).toMillis()));

        assertThrows(InvalidArgumentException.class,
                () -> service.create(request, user("alice", "org-a")));
    }

    private ShareCreateReq request(ShareIdentityPolicy policy) {
        ShareCreateReq request = new ShareCreateReq();
        request.setDashboardId(12L);
        request.setIdentityPolicy(policy);
        request.setAllowedUsers(policy == ShareIdentityPolicy.USERS ? List.of("bob") : List.of());
        request.setExpiresAt(new Date(System.currentTimeMillis() + 3_600_000));
        request.setMaxAccessCount(10);
        request.setWatermarkEnabled(true);
        return request;
    }

    private User user(String name, String organization) {
        User user = User.get(1L, name);
        user.getAttributes().put("organizationId", organization);
        return user;
    }
}
