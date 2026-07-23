package com.tencent.supersonic.headless.api.pojo.enums;

/** Stable error taxonomy shared by NL2SQL generation, correction and evaluation. */
public enum SqlErrorType {
    NONE, MAPPING_ERROR, DEFINITION_ERROR, JOIN_ERROR, FILTER_ERROR, SYNTAX_ERROR, EXECUTION_ERROR
}
