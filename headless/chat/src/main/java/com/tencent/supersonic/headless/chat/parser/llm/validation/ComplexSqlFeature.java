package com.tencent.supersonic.headless.chat.parser.llm.validation;

public enum ComplexSqlFeature {
    SINGLE_TABLE,
    MULTI_TABLE,
    NESTED_QUERY,
    AGGREGATION,
    WINDOW_FUNCTION,
    YOY,
    MOM,
    TOP_N,
    CROSS_ORGANIZATION
}
