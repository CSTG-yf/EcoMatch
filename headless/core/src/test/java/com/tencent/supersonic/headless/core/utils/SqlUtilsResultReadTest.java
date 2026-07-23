package com.tencent.supersonic.headless.core.utils;

import com.tencent.supersonic.common.pojo.QueryColumn;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlUtilsResultReadTest {

    @Test
    void propagatesResultSetFailureInsteadOfReturningPartialRows() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenThrow(new SQLException("driver read timeout"));

        assertThrows(SQLException.class, () -> new SqlUtils().getAllData(resultSet,
                List.of(new QueryColumn("account_no", "VARCHAR"))));
    }
}
