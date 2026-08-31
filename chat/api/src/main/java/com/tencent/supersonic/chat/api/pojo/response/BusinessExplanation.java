package com.tencent.supersonic.chat.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessExplanation {

    private String summary;
    private double confidence;
    private String timeRange;
    @Builder.Default
    private List<String> evidence = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    @Builder.Default
    private Map<String, String> metricDefinitions = new LinkedHashMap<>();
}
