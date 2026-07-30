package com.tencent.supersonic.chat.api.pojo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChartFeedbackReq {

    @NotNull
    @Positive
    private Long queryId;

    @NotBlank
    @Size(max = 32)
    private String recommendedChart;

    @NotBlank
    @Size(max = 32)
    private String selectedChart;

    @NotBlank
    @Size(max = 32)
    private String source;
}
