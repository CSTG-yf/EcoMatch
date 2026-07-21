package com.tencent.supersonic.headless.server.pojo.bank;

import lombok.Data;

@Data
public class BankSemanticImportConfig {

    private Long modelId;

    private String dataSetName = "银行业智能问数数据集";

    private String dataSetBizName = "bank_indicator_dataset";

    private String dateField = "data_date";

    private String organizationField = "organization_code";

    private String indicatorCodeField = "metric_code";

    private String indicatorValueField = "metric_value";
}
