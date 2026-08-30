package com.tencent.supersonic.headless.core.utils;

import com.tencent.supersonic.headless.api.pojo.bank.BankDataDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The execution-path seam that lazily initializes the bank data-date domain from the first live
 * JDBC connection. Failures must stay silent so execution is never affected by the probe.
 */
class SqlUtilsBankDataDomainProbeTest {

    @BeforeEach
    @AfterEach
    void resetDataDomain() {
        BankDataDomain.reset();
    }

    @Test
    void initializesTheDomainFromTheFirstLiveConnectionObservation() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject(1)).thenReturn(Date.valueOf("2024-01-01"));
        when(resultSet.getObject(2)).thenReturn(Date.valueOf("2025-12-31"));

        SqlUtils.probeBankDataDomain(connection);

        BankDataDomain domain = BankDataDomain.current();
        assertNotNull(domain);
        assertEquals(LocalDate.of(2024, 1, 1), domain.minDataDate());
        assertEquals(LocalDate.of(2025, 12, 31), domain.maxDataDate());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        assertTrue(sql.getValue().contains("bank_metric_daily"));
        assertTrue(sql.getValue().contains("MIN(data_date)"));
        assertTrue(sql.getValue().contains("MAX(data_date)"));
    }

    @Test
    void staysSilentWhenTheConnectionOrQueryFailsAndLeavesTheGuardFallOpen() throws Exception {
        Connection brokenConnection = mock(Connection.class);
        when(brokenConnection.createStatement()).thenThrow(new SQLException("pool exhausted"));
        assertDoesNotThrow(() -> SqlUtils.probeBankDataDomain(brokenConnection));
        assertNull(BankDataDomain.current());

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("table missing"));
        assertDoesNotThrow(() -> SqlUtils.probeBankDataDomain(connection));
        assertNull(BankDataDomain.current());
    }

    @Test
    void ignoresANullConnectionAndAnEmptyObservation() throws Exception {
        assertDoesNotThrow(() -> SqlUtils.probeBankDataDomain(null));
        assertNull(BankDataDomain.current());

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        SqlUtils.probeBankDataDomain(connection);
        assertNull(BankDataDomain.current());
    }

    @Test
    void doesNotReobserveOnceTheDomainIsAlreadyCached() throws Exception {
        assertTrue(BankDataDomain.tryInitialize(LocalDate.of(2024, 1, 1),
                LocalDate.of(2025, 12, 31)));

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new IllegalStateException(
                "must never query once the domain is cached"));

        SqlUtils.probeBankDataDomain(connection);

        assertEquals(LocalDate.of(2024, 1, 1), BankDataDomain.current().minDataDate());
        assertEquals(LocalDate.of(2025, 12, 31), BankDataDomain.current().maxDataDate());
    }
}
