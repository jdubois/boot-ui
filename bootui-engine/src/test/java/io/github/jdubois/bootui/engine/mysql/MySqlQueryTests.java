package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MySqlQueryTests {
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void requiredMetadataNeverInfersValuesFromEmptyOrAmbiguousRows(int count) throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> MySqlJdbcFixture.row("value", "1"))
                .toList();
        assertThatThrownBy(() -> MySqlQuery.requiredRow(
                        fixture.connection,
                        new MySqlReadBudget(Duration.ofSeconds(15), () -> 0),
                        "SELECT value FROM fixed LIMIT ?"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("BUI04"));
    }

    @Test
    void showFallbackHasAFixedNameInventoryAndNeverUsesJdbcCancellation() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        Statement statement = mock(Statement.class);
        when(fixture.connection.createStatement()).thenReturn(statement);
        var resultSet = MySqlJdbcFixture.rows(List.of(MySqlJdbcFixture.row("variable_name", "Uptime", "value", "10")));
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        var result = MySqlQuery.status(fixture.connection, new MySqlReadBudget(Duration.ofSeconds(15), () -> 0));
        assertThat(result.values()).hasSize(1);
        assertThat(result.reason()).isNull();
        assertThat(result.truncated()).isFalse();
        assertThat(MySqlCollectors.STATUS_NAMES.split(",")).hasSize(17);
        verify(statement).setMaxRows(18);
        verify(statement)
                .executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN (" + MySqlCollectors.STATUS_NAMES + ")");
        verify(statement, never()).setQueryTimeout(anyInt());
    }

    @Test
    void materializationBudgetKeepsCompletedRowsWithoutPretendingTheCapWasExceeded() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        fixture.results = sql -> List.of(
                MySqlJdbcFixture.row("value", "9007199254740993"), MySqlJdbcFixture.row("value", "9007199254740994"));
        fixture.duringMaterialization = sql -> time.set(11_000_000);
        MySqlQuery.Rows rows = MySqlQuery.read(
                fixture.connection,
                new MySqlReadBudget(Duration.ofMillis(10), time::get),
                "SELECT value FROM fixed LIMIT ?",
                10);
        assertThat(rows.values()).containsExactly(MySqlJdbcFixture.row("value", "9007199254740993"));
        assertThat(rows.truncated()).isFalse();
        assertThat(rows.reason()).contains("budget exhausted while consuming", "remaining rows are unknown");
    }

    @Test
    void expirationBeforeTheFirstRowIsNotACompleteEmptyResult() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        fixture.results = sql -> List.of(MySqlJdbcFixture.row("value", "1"));
        fixture.beforeQuery = () -> time.set(11_000_000);
        MySqlQuery.Rows rows = MySqlQuery.read(
                fixture.connection,
                new MySqlReadBudget(Duration.ofMillis(10), time::get),
                "SELECT value FROM fixed LIMIT ?",
                10);
        assertThat(rows.values()).isEmpty();
        assertThat(rows.truncated()).isFalse();
        assertThat(rows.reason()).contains("budget exhausted while consuming");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3})
    void truncationNeedsAnActualExtraRow(int count) throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> MySqlJdbcFixture.row("counter", "18446744073709551615"))
                .toList();
        MySqlQuery.Rows rows = MySqlQuery.read(
                fixture.connection,
                new MySqlReadBudget(Duration.ofSeconds(15), () -> 0),
                "SELECT counter FROM fixed WHERE schema_name=? LIMIT ?",
                2,
                "fixture");
        assertThat(rows.values()).hasSize(Math.min(count, 2));
        assertThat(rows.truncated()).isEqualTo(count > 2);
        verify(fixture.prepared.get(0)).setInt(2, 3);
        verify(fixture.prepared.get(0)).setMaxRows(3);
        verify(fixture.prepared.get(0), never()).setQueryTimeout(anyInt());
        assertThat(fixture.sql).singleElement().asString().contains("MAX_EXECUTION_TIME(5000)");
    }

    @Test
    void expiredBudgetDoesNotPrepareSqlAndMalformedCollectorSqlIsRejected() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        MySqlReadBudget budget = new MySqlReadBudget(Duration.ofMillis(10), time::get);
        time.set(11_000_000);
        assertThatThrownBy(() -> MySqlQuery.read(fixture.connection, budget, "SELECT a LIMIT ?", 2))
                .isInstanceOf(java.sql.SQLTimeoutException.class);
        assertThat(fixture.sql).isEmpty();
        assertThatThrownBy(() -> MySqlQuery.read(
                        fixture.connection, new MySqlReadBudget(Duration.ofSeconds(1), () -> 0), "SELECT unbounded", 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorsNeverExposeVendorMessages() {
        for (SQLException error : List.of(
                new SQLException("password=secret", "42000", 1142),
                new SQLException("password=secret", "HY000", 3024),
                new SQLException("password=secret", "08001"),
                new SQLException("password=secret", "HY000"))) {
            assertThat(MySqlQuery.reason(error)).doesNotContain("password", "secret");
        }
    }
}
