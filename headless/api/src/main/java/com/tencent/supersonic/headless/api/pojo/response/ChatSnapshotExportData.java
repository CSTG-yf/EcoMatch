package com.tencent.supersonic.headless.api.pojo.response;

import com.tencent.supersonic.common.pojo.QueryColumn;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side snapshot of a chat query answer used for snapshot export. The data is resolved
 * from persisted chat history, never submitted by the client.
 */
@Data
@Builder
public class ChatSnapshotExportData {

    private Long queryId;

    private String question;

    private Long dataSetId;

    private List<QueryColumn> columns;

    private List<Map<String, Object>> rows;

    private boolean masked;

    private Set<String> maskedColumns;

    /** Business conclusion text generated with the answer, if any. */
    private String conclusion;

    private String chartType;

    /** Data date range inferred from the snapshot (dateInfo or date columns), if any. */
    private String dateRange;
}
