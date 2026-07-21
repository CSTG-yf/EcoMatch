package com.tencent.supersonic.headless.server.service.governance;

import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.MetricDefineByFieldParams;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.server.pojo.governance.MetricVersionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MetricVersionSnapshotTest {

    @Test
    void shouldRoundTripMetricDefinitionAndGovernanceMetadata() {
        MetricResp metric = new MetricResp();
        metric.setId(101L);
        metric.setModelId(10L);
        metric.setName("各项存款余额");
        metric.setBizName("zb001");
        metric.setMetricDefineType(MetricDefineType.FIELD);
        MetricDefineByFieldParams params = new MetricDefineByFieldParams();
        params.setExpr("SUM(metric_value)");
        metric.setMetricDefineByFieldParams(params);

        MetricVersionSnapshot snapshot = new MetricVersionSnapshot();
        snapshot.setMetric(metric);
        snapshot.setOwnerDepartment("计划财务部");
        snapshot.setSourceSystem("监管报送系统");
        snapshot.setBusinessDefinition("各项存款期末余额");
        snapshot.setEffectiveFrom(new Date(1704038400000L));

        MetricVersionSnapshot restored =
                JsonUtil.toObject(JsonUtil.toString(snapshot), MetricVersionSnapshot.class);

        assertNotNull(restored);
        assertEquals("计划财务部", restored.getOwnerDepartment());
        assertEquals("SUM(metric_value)",
                restored.getMetric().getMetricDefineByFieldParams().getExpr());
        assertEquals(MetricDefineType.FIELD, restored.getMetric().getMetricDefineType());
    }
}
