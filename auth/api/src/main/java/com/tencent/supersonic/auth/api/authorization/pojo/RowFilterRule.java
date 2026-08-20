package com.tencent.supersonic.auth.api.authorization.pojo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Structured, whitelistable row-level condition. */
@Data
public class RowFilterRule {

    private String field;
    private String operator;
    private List<String> values = new ArrayList<>();
    private String valueSource = "CONSTANT";
}
