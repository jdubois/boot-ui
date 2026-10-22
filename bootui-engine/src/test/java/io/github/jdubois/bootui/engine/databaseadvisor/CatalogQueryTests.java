package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CatalogQueryTests {

    @Test
    void cooperativeDeadlineRetainsAlreadyMappedPositiveRows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, true, false);
        AtomicLong time = new AtomicLong();
        VendorAugmentation<PostgresExtensionTable> augmentation = CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_EXTENSION_TABLES,
                "select table_name from catalog limit ?",
                ScanBudget.of(Duration.ofSeconds(1), time::get),
                DatabaseAdvisorLimits.DEFAULTS,
                row -> {
                    time.set(Duration.ofSeconds(2).toNanos());
                    return new PostgresExtensionTable("public", "known", "extension");
                });
        assertThat(augmentation.findings()).hasSize(1);
        assertThat(augmentation.truncated()).isTrue();
        verify(statement).setQueryTimeout(1);
    }

    @Test
    void nullMappedRowsStillConsumeTheRawBoundAndReportTruncation() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, true, true, false);
        AtomicInteger mapped = new AtomicInteger();
        DatabaseAdvisorLimits limits =
                new DatabaseAdvisorLimits(10, 10, 10, 2, Duration.ofSeconds(30), Duration.ofSeconds(5));
        VendorAugmentation<PostgresExtensionTable> augmentation = CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_EXTENSION_TABLES,
                "select table_name from catalog limit ?",
                ScanBudget.of(Duration.ofSeconds(30)),
                limits,
                row -> {
                    mapped.incrementAndGet();
                    return null;
                });
        assertThat(augmentation.available()).isTrue();
        assertThat(augmentation.findings()).isEmpty();
        assertThat(augmentation.truncated()).isTrue();
        assertThat(mapped).hasValue(2);
        verify(statement).setMaxRows(3);
        verify(statement).close();
        verify(result).close();
    }

    @Test
    void theFixedStatementModeKeepsEveryGuardAndBindsNothing() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement("show session variables where Variable_name in ('wait_timeout')"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, true, true, false);
        when(result.getString("Value")).thenReturn("1");
        DatabaseAdvisorLimits limits =
                new DatabaseAdvisorLimits(10, 10, 10, 2, Duration.ofSeconds(30), Duration.ofSeconds(4));

        VendorAugmentation<ServerSetting> augmentation = CatalogQuery.readFixed(
                connection,
                VendorFindingKinds.MYSQL_SERVER_SETTINGS,
                "show session variables where Variable_name in ('wait_timeout')",
                ScanBudget.of(Duration.ofSeconds(30)),
                limits,
                row -> new ServerSetting("wait_timeout", row.getString("Value"), null));

        assertThat(augmentation.available()).isTrue();
        assertThat(augmentation.findings()).hasSize(2);
        assertThat(augmentation.truncated()).isTrue();
        verify(statement).setQueryTimeout(4);
        verify(statement).setMaxRows(3);
        verify(statement, never()).setInt(anyInt(), anyInt());
        verify(statement, never()).setObject(anyInt(), any());
        verify(statement).close();
        verify(result).close();
    }

    @Test
    void theFixedStatementModeCapturesAFailureAndHonoursAnExhaustedBudget() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("denied"));
        VendorAugmentation<ServerSetting> failed = CatalogQuery.readFixed(
                connection,
                VendorFindingKinds.MYSQL_SERVER_SETTINGS,
                "show session variables",
                ScanBudget.of(Duration.ofSeconds(30)),
                DatabaseAdvisorLimits.DEFAULTS,
                row -> null);
        assertThat(failed.failed()).isTrue();
        assertThat(failed.reason()).contains("MySQL/MariaDB server settings could not be read", "denied");

        AtomicLong time = new AtomicLong();
        ScanBudget spent = ScanBudget.of(Duration.ofSeconds(1), time::get);
        time.set(Duration.ofSeconds(2).toNanos());
        VendorAugmentation<ServerSetting> exhausted = CatalogQuery.readFixed(
                connection,
                VendorFindingKinds.MYSQL_SERVER_SETTINGS,
                "show session variables",
                spent,
                DatabaseAdvisorLimits.DEFAULTS,
                row -> null);
        assertThat(exhausted.failed()).isTrue();
        assertThat(exhausted.reason()).contains("scan budget ran out");
    }
}
