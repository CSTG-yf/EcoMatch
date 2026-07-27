package com.tencent.supersonic.common.util;

import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlFilterUtilsSecurityTest {

    private final SqlFilterUtils sqlFilterUtils = new SqlFilterUtils();

    @Test
    void quotedComparisonValueCannotEscapeStringLiteral() {
        Filter filter =
                new Filter("customer_name", FilterOperatorEnum.EQUALS, "'alice' OR '1'='1'");

        assertEquals(" customer_name = 'alice'' OR ''1''=''1' ",
                sqlFilterUtils.getWhereClause(List.of(filter)));
    }

    @Test
    void quotedInValueCannotEscapeStringLiteral() {
        Filter filter =
                new Filter("customer_name", FilterOperatorEnum.IN, List.of("'alice') OR 1=1 --'"));

        assertEquals(" customer_name IN ('alice'') OR 1=1 --') ",
                sqlFilterUtils.getWhereClause(List.of(filter)));
    }

    @Test
    void quotedLikeValueCannotEscapeStringLiteral() {
        Filter filter = new Filter("customer_name", FilterOperatorEnum.LIKE, "'alice' OR '1'='1'");

        assertEquals(" customer_name LIKE 'alice'' OR ''1''=''1' ",
                sqlFilterUtils.getWhereClause(List.of(filter)));
    }

    @Test
    void ordinaryStringSemanticsRemainStable() {
        Filter equals = new Filter("customer_name", FilterOperatorEnum.EQUALS, "O'Brien");
        Filter like = new Filter("customer_name", FilterOperatorEnum.LIKE, "Alice");

        assertEquals(" customer_name = 'O''Brien' AND customer_name LIKE 'Alice%' ",
                sqlFilterUtils.getWhereClause(List.of(equals, like)));
    }
}
