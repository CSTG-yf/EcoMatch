package com.tencent.supersonic.headless.server.aspect;

import com.tencent.supersonic.headless.api.pojo.DimValueMap;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.service.DimensionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DimValueAspectTest {

    @Test
    void shouldMapCaseVariantAliasToTechnicalValueForPhysicalSqlField() throws Throwable {
        DimensionService dimensionService = mock(DimensionService.class);
        when(dimensionService.getDimensions(any())).thenReturn(List.of(organizationDimension()));

        DimValueAspect aspect = new DimValueAspect();
        Field serviceField = DimValueAspect.class.getDeclaredField("dimensionService");
        serviceField.setAccessible(true);
        serviceField.set(aspect, dimensionService);

        QuerySqlReq request = new QuerySqlReq();
        request.addModelId(8L);
        request.setSql("SELECT org_code FROM t_33 WHERE org_code = 'bank d'");
        request.getSqlInfo().setParsedS2SQL("SELECT Organization FROM bank_daily_metrics");
        request.getSqlInfo().setCorrectedS2SQL("SELECT Organization FROM bank_daily_metrics");
        request.getSqlInfo().setQuerySQL(request.getSql());

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request});
        doAnswer(invocation -> {
            assertTrue(request.getSql().contains("org_code = 'ORG004'"));
            assertTrue(request.getSqlInfo().getQuerySQL().contains("org_code = 'ORG004'"));
            return new SemanticQueryResp();
        }).when(joinPoint).proceed();

        aspect.handleSqlDimValue(joinPoint);
    }

    private DimensionResp organizationDimension() {
        DimValueMap valueMap = new DimValueMap();
        valueMap.setTechName("ORG004");
        valueMap.setBizName("Bank D");
        valueMap.setValue("ORG004");
        valueMap.setAlias(List.of("ORG004", "Bank D"));

        DimensionResp dimension = new DimensionResp();
        dimension.setName("Organization");
        dimension.setBizName("bank_organization");
        dimension.setExpr("org_code");
        dimension.setDimValueMaps(List.of(valueMap));
        return dimension;
    }
}
