package com.tencent.supersonic.headless.server.pojo.governance;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MetricConflict {

    private String type;
    private String severity;
    private String key;
    private String message;
    private List<Long> metricIds;
    private List<Long> mappingIds;
}
