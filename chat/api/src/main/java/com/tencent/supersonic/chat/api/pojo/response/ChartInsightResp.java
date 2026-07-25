package com.tencent.supersonic.chat.api.pojo.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChartInsightResp {

    private ChartRecommendation recommendedChart;
    private List<ChartRecommendation> candidateCharts;
}
