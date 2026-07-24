package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.util.ResultFormatter;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankResultProjector;

import java.util.List;

/** Applies a bank-specific presentation contract after semantic execution has completed. */
public class BankResultProjectionHandler {

    private final BankResultProjector projector = new BankResultProjector();

    public boolean apply(QueryResult queryResult) {
        if (queryResult == null || queryResult.getChatContext() == null) {
            return false;
        }
        BankResultProjector.Contract contract = contract(queryResult.getChatContext());
        BankResultProjector.Projection projection =
                projector.project(contract, queryResult.getQueryResults());
        if (!projection.isApplied()) {
            return false;
        }
        List<QueryColumn> columns = projection.getColumns().stream()
                .map(column -> new QueryColumn(column, "STRING", column)).toList();
        queryResult.setQueryColumns(columns);
        queryResult.setQueryResults(projection.getRows());
        queryResult.setTextResult(ResultFormatter.transform2TextNew(columns, projection.getRows()));
        return true;
    }

    private BankResultProjector.Contract contract(SemanticParseInfo parseInfo) {
        Object value = parseInfo.getProperties().get(BankResultProjector.CONTRACT_PROPERTY);
        if (value instanceof BankResultProjector.Contract contract) {
            return contract;
        }
        if (value == null) {
            return null;
        }
        try {
            return JsonUtil.toObject(JsonUtil.toString(value), BankResultProjector.Contract.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
