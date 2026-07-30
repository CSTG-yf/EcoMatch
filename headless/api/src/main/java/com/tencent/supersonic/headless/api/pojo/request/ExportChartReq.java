package com.tencent.supersonic.headless.api.pojo.request;

import com.tencent.supersonic.headless.api.pojo.enums.ExportChartType;
import lombok.Data;

@Data
public class ExportChartReq {

    private Integer queryIndex;

    private ExportChartType type;

    private String title;

    private String categoryField;

    private String valueField;
}
