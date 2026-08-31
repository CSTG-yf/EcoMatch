package com.tencent.supersonic.chat.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartRecommendation {

    private String chartType;
    private double confidence;
    private String reason;
    @Builder.Default
    private List<String> dimensionFields = new ArrayList<>();
    @Builder.Default
    private List<String> metricFields = new ArrayList<>();
}
