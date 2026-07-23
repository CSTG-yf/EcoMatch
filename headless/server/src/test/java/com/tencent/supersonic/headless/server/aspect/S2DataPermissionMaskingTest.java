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
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.QueryStructUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S2DataPermissionMaskingTest {

    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    private final SchemaService schemaService = mock(SchemaService.class);
    private final ModelService modelService = mock(ModelService.class);
    private final QueryStructUtils queryStructUtils = mock(QueryStructUtils.class);
    private final AuthService authService = mock(AuthService.class);
    private final S2DataPermissionAspect aspect = new S2DataPermissionAspect();
    private final User analyst = User.get(2L, "analyst");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(aspect, "schemaService", schemaService);
        ReflectionTestUtils.setField(aspect, "modelService", modelService);
        ReflectionTestUtils.setField(aspect, "queryStructUtils", queryStructUtils);
        ReflectionTestUtils.setField(aspect, "authService", authService);
        ReflectionTestUtils.setField(aspect, "dataMaskingService", new DataMaskingService("", ""));
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
    }

    @Test
    void rejectsAuthorizedQueryWhenModelScopeCannotBeResolved() {
        QueryStructReq request = new QueryStructReq();
        request.setNeedAuth(true);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request, analyst});
        when(queryStructUtils.getModelIdsFromStruct(eq(request), any())).thenReturn(Set.of());

        assertThrows(InvalidArgumentException.class, () -> aspect.doAround(joinPoint));
    }

    @Test
    void deniesQueryWhenRowPermissionExpressionContainsSqlInjection() {
        assertDeniedFilter("1=1; DROP TABLE account");
    }

    @Test
    void deniesQueryWhenRowPermissionExpressionCannotBeParsed() {
        assertDeniedFilter(")");
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
