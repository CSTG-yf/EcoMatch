package com.tencent.supersonic.chat.api.pojo.request;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class BusinessInsightReq {

    private String queryText;
    private List<QueryColumn> queryColumns = new ArrayList<>();
    private List<Map<String, Object>> queryResults = new ArrayList<>();
    private Set<SchemaElement> metrics = new HashSet<>();
}
