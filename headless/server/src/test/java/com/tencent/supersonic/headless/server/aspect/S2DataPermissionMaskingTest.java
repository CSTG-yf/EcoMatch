package com.tencent.supersonic.headless.server.aspect;

import com.tencent.supersonic.auth.api.authorization.pojo.DimensionFilter;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.server.security.DataMaskingService;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.QueryStructUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S2DataPermissionMaskingTest {

    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    private final SchemaService schemaService = mock(SchemaService.class);
    private final ModelService modelService = mock(ModelService.class);
    private final QueryStructUtils queryStructUtils = mock(QueryStructUtils.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditEventPublisher auditEventPublisher = mock(AuditEventPublisher.class);
    private final S2DataPermissionAspect aspect = new S2DataPermissionAspect();
    private final User analyst = User.get(2L, "analyst");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(aspect, "schemaService", schemaService);
        ReflectionTestUtils.setField(aspect, "modelService", modelService);
        ReflectionTestUtils.setField(aspect, "queryStructUtils", queryStructUtils);
        ReflectionTestUtils.setField(aspect, "authService", authService);
        ReflectionTestUtils.setField(aspect, "dataMaskingService", new DataMaskingService("", ""));
        ReflectionTestUtils.setField(aspect, "auditEventPublisher", auditEventPublisher);
        when(schemaService.fetchSemanticSchema(any())).thenReturn(schema());
    }

    @Test
    void masksEvenWhenAuthorizationChecksAreDisabled() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(false);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(joinPoint.proceed()).thenReturn(response());

        SemanticQueryResp result = (SemanticQueryResp) aspect.doAround(joinPoint);

        assertEquals("138****5678", result.getResultList().get(0).get("mobile"));
        List<AuditEvent> events = capturedEvents(2);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_ALLOWED, "AUTH_NOT_REQUIRED");
        assertSingleMaskEvent(events);
    }

    @Test
    void deniesResultWhenSemanticSchemaIsUnavailable() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(false);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(joinPoint.proceed()).thenReturn(response());
        when(schemaService.fetchSemanticSchema(any())).thenReturn(null);

        assertThrows(InvalidPermissionException.class, () -> aspect.doAround(joinPoint));
    }

    @Test
    void masksModelAdministratorWithoutRawDataRole() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(true);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(joinPoint.proceed()).thenReturn(response());
        when(queryStructUtils.getModelIdsFromStruct(eq(request), any())).thenReturn(Set.of(1L));
        ModelResp model = new ModelResp();
        model.setId(1L);
        when(modelService.getModelListWithAuth(eq(analyst), isNull(), eq(AuthType.ADMIN)))
                .thenReturn(List.of(model));

        SemanticQueryResp result = (SemanticQueryResp) aspect.doAround(joinPoint);

        assertEquals("138****5678", result.getResultList().get(0).get("mobile"));
        List<AuditEvent> events = capturedEvents(2);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_ALLOWED, "AUTH_MODEL_ADMIN");
        assertSingleMaskEvent(events);
    }

    @Test
    void recordsOneAuthorizationDecisionForPolicyAuthorizedQuery() throws Throwable {
        QueryStructReq request = authorizedPolicyRequest();
        when(joinPoint.proceed()).thenReturn(response());

        SemanticQueryResp result = (SemanticQueryResp) aspect.doAround(joinPoint);

        assertEquals("138****5678", result.getResultList().get(0).get("mobile"));
        List<AuditEvent> events = capturedEvents(2);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_ALLOWED,
                "AUTH_POLICY_ALLOWED");
        assertSingleMaskEvent(events);
    }

    @Test
    void doesNotRecordMaskEventWhenResponseWasNotMasked() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(false);
        request.setDataSetId(42L);
        SemanticQueryResp response = response();
        response.setResultList(List.of());
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(joinPoint.proceed()).thenReturn(response);

        aspect.doAround(joinPoint);

        List<AuditEvent> events = capturedEvents(1);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_ALLOWED, "AUTH_NOT_REQUIRED");
        assertEquals("DATASET", events.get(0).getResourceType());
        assertEquals("id=42", events.get(0).getResourceId());
    }

    @Test
    void rejectsAuthorizedQueryWhenModelScopeCannotBeResolved() {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(true);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(queryStructUtils.getModelIdsFromStruct(eq(request), any())).thenReturn(Set.of());

        assertThrows(InvalidArgumentException.class, () -> aspect.doAround(joinPoint));

        List<AuditEvent> events = capturedEvents(1);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_DENIED,
                "AUTH_MODEL_SCOPE_UNRESOLVED");
        AuditEvent denied = events.get(0);
        assertEquals(AuditOutcome.DENIED, denied.getOutcome());
        assertEquals("MODEL_SCOPE", denied.getResourceType());
        assertEquals("unresolved", denied.getResourceId());
        assertNull(denied.getRawQuestion());
        assertNull(denied.getRawSql());
        assertNull(denied.getMetadata());
    }

    @Test
    void deniesQueryWhenRowPermissionExpressionContainsSqlInjection() {
        assertDeniedFilter("1=1; DROP TABLE account");
    }

    @Test
    void deniesQueryWhenRowPermissionExpressionCannotBeParsed() {
        assertDeniedFilter(")");
    }

    @Test
    void businessFailureAfterAuthorizationDoesNotCreateDeniedDecision() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(false);
        RuntimeException businessFailure = new RuntimeException("query failed");
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(joinPoint.proceed()).thenThrow(businessFailure);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> aspect.doAround(joinPoint));

        assertSame(businessFailure, thrown);
        List<AuditEvent> events = capturedEvents(1);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_ALLOWED, "AUTH_NOT_REQUIRED");
    }

    @Test
    void authorizationAuditFailureStopsAuthorizedQuery() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(false);
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(auditEventPublisher.publishRequired(any(), eq(analyst))).thenThrow(auditFailure);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> aspect.doAround(joinPoint));

        assertSame(auditFailure, thrown);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void deniedAuditFailureIsSuppressedWithoutReplacingPermissionFailure() {
        QueryStructReq request = authorizedPolicyRequest();
        DimensionFilter filter = new DimensionFilter();
        filter.setExpressions(List.of("1=1; DROP TABLE account"));
        AuthorizedResourceResp authorization = new AuthorizedResourceResp();
        authorization.setFilters(List.of(filter));
        when(authService.queryAuthorizedResources(any(), eq(analyst))).thenReturn(authorization);
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(auditEventPublisher.publishRequired(any(), eq(analyst))).thenThrow(auditFailure);

        InvalidPermissionException thrown =
                assertThrows(InvalidPermissionException.class, () -> aspect.doAround(joinPoint));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(auditFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void maskingAuditFailurePreventsMaskedResultFromBeingReturned() throws Throwable {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(false);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(joinPoint.proceed()).thenReturn(response());
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(auditEventPublisher.publishRequired(any(), eq(analyst))).thenReturn("auth-event")
                .thenThrow(auditFailure);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> aspect.doAround(joinPoint));

        assertSame(auditFailure, thrown);
        verify(auditEventPublisher, times(2)).publishRequired(any(), eq(analyst));
    }

    private void assertDeniedFilter(String expression) {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(true);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(queryStructUtils.getModelIdsFromStruct(eq(request), any())).thenReturn(Set.of(1L));
        when(queryStructUtils.getBizNameFromStruct(request)).thenReturn(Set.of());

        ModelResp model = new ModelResp();
        model.setId(1L);
        when(modelService.getModelListWithAuth(eq(analyst), isNull(), eq(AuthType.ADMIN)))
                .thenReturn(List.of());
        when(modelService.getModelListWithAuth(eq(analyst), isNull(), eq(AuthType.VIEWER)))
                .thenReturn(List.of(model));

        DimensionFilter filter = new DimensionFilter();
        filter.setExpressions(List.of(expression));
        AuthorizedResourceResp authorization = new AuthorizedResourceResp();
        authorization.setFilters(List.of(filter));
        when(authService.queryAuthorizedResources(any(), eq(analyst))).thenReturn(authorization);

        assertThrows(InvalidPermissionException.class, () -> aspect.doAround(joinPoint));

        List<AuditEvent> events = capturedEvents(1);
        assertSingleAuthorizationDecision(events, AuditEventType.AUTH_DENIED,
                "AUTH_ROW_POLICY_DENIED");
        AuditEvent denied = events.get(0);
        assertEquals("MODEL_SCOPE", denied.getResourceType());
        assertEquals("ids=1", denied.getResourceId());
        assertNull(denied.getRawQuestion());
        assertNull(denied.getRawSql());
        assertNull(denied.getMetadata());
    }

    private QueryStructReq authorizedPolicyRequest() {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(true);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(queryStructUtils.getModelIdsFromStruct(eq(request), any())).thenReturn(Set.of(1L));
        when(queryStructUtils.getBizNameFromStruct(request)).thenReturn(Set.of());

        ModelResp model = new ModelResp();
        model.setId(1L);
        when(modelService.getModelListWithAuth(eq(analyst), isNull(), eq(AuthType.ADMIN)))
                .thenReturn(List.of());
        when(modelService.getModelListWithAuth(eq(analyst), isNull(), eq(AuthType.VIEWER)))
                .thenReturn(List.of(model));
        when(authService.queryAuthorizedResources(any(), eq(analyst)))
                .thenReturn(new AuthorizedResourceResp());
        return request;
    }

    private List<AuditEvent> capturedEvents(int expectedCount) {
        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, times(expectedCount)).publishRequired(eventCaptor.capture(),
                eq(analyst));
        return eventCaptor.getAllValues();
    }

    private void assertSingleAuthorizationDecision(List<AuditEvent> events,
            AuditEventType expectedType, String expectedReasonCode) {
        List<AuditEvent> authorizationEvents =
                events.stream().filter(event -> event.getEventType() == AuditEventType.AUTH_ALLOWED
                        || event.getEventType() == AuditEventType.AUTH_DENIED).toList();
        assertEquals(1, authorizationEvents.size());
        assertEquals(expectedType, authorizationEvents.get(0).getEventType());
        assertEquals(expectedReasonCode, authorizationEvents.get(0).getReasonCode());
    }

    private void assertSingleMaskEvent(List<AuditEvent> events) {
        List<AuditEvent> maskEvents = events.stream()
                .filter(event -> event.getEventType() == AuditEventType.MASK_APPLIED).toList();
        assertEquals(1, maskEvents.size());
        assertEquals(AuditOutcome.SUCCESS, maskEvents.get(0).getOutcome());
        assertEquals("maskedColumnCount=1", maskEvents.get(0).getMaskingSummary());
        assertTrue(maskEvents.get(0).getResourceId().startsWith("ids=")
                || "unresolved".equals(maskEvents.get(0).getResourceId()));
    }

    private SemanticSchemaResp schema() {
        DimSchemaResp dimension = new DimSchemaResp();
        dimension.setName("mobile");
        dimension.setBizName("mobile");
        dimension.setSensitiveLevel(SensitiveLevelEnum.HIGH.getCode());
        SemanticSchemaResp schema = new SemanticSchemaResp();
        schema.setDimensions(List.of(dimension));
        return schema;
    }

    private SemanticQueryResp response() {
        SemanticQueryResp response = new SemanticQueryResp();
        response.setColumns(List.of(new QueryColumn("mobile", "VARCHAR", "mobile")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mobile", "13812345678");
        response.setResultList(List.of(row));
        return response;
    }
}
