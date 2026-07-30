package com.tencent.supersonic.headless.api.pojo.request;

import com.tencent.supersonic.headless.api.pojo.enums.ExportFormat;
import com.tencent.supersonic.headless.api.pojo.enums.ExportResourceType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExportCreateReq {

    private ExportResourceType resourceType;

    private Long dashboardId;

    private ExportFormat format;

    private String title;

    /**
     * Structured query intents are always executed again by the server. Result snapshots and raw
     * SQL are deliberately not part of this protocol.
     */
    private List<QueryStructReq> queries = new ArrayList<>();

    private List<ExportChartReq> charts = new ArrayList<>();
}
