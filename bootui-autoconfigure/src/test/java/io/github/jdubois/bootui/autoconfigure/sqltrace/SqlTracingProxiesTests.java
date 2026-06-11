package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.CapturedStatement;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Operation;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the hand-written JDBC tracing proxy intercepts the right calls while
 * delegating everything else. JDBC objects are Mockito mocks so the proxy can be
 * exercised without a live database.
 */
class SqlTracingProxiesTests {

    private SqlTraceRecorder recorder() {
        return new SqlTraceRecorder(true, true, 100, 100, 2000, 200);
    }

    @Test
    void capturesPreparedQueryWithParametersAndConnectionId() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        String sql = "select * from account where id = ?";
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(sql)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        try (Connection c = traced.getConnection()) {
            PreparedStatement p = c.prepareStatement(sql);
            p.setLong(1, 42L);
            assertThat(p.executeQuery()).isSameAs(rs);
        }

        assertThat(recorder.recent()).hasSize(1);
        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.sql()).isEqualTo(sql);
        assertThat(entry.statementType()).isEqualTo(StatementType.PREPARED);
        assertThat(entry.operation()).isEqualTo(Operation.QUERY);
        assertThat(entry.success()).isTrue();
        assertThat(entry.connectionId()).startsWith("conn-");
        assertThat(entry.parameters()).containsExactly("42");
    }

    @Test
    void capturesUpdateCountAndOperation() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("update account set name = ?")).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(3);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        PreparedStatement p = traced.getConnection().prepareStatement("update account set name = ?");
        p.setString(1, "alice");
        assertThat(p.executeUpdate()).isEqualTo(3);

        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.operation()).isEqualTo(Operation.UPDATE);
        assertThat(entry.affectedRows()).isEqualTo(3L);
        assertThat(entry.parameters()).containsExactly("'alice'");
    }

    @Test
    void capturesPlainStatementAndClassifiesByKeyword() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.execute("SELECT 1")).thenReturn(true);
        when(stmt.executeQuery("SELECT 1")).thenReturn(rs);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        Statement s = traced.getConnection().createStatement();
        s.executeQuery("SELECT 1");
        s.execute("SELECT 1");

        assertThat(recorder.recent())
                .extracting(CapturedStatement::statementType)
                .containsOnly(StatementType.STATEMENT);
        assertThat(recorder.recent())
                .extracting(CapturedStatement::operation)
                .containsExactly(Operation.QUERY, Operation.QUERY);
    }

    @Test
    void capturesBatchSizeAndSummedRows() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("insert into account(name) values (?)")).thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[] {1, 1, 1});

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        PreparedStatement p = traced.getConnection().prepareStatement("insert into account(name) values (?)");
        p.setString(1, "a");
        p.addBatch();
        p.setString(1, "b");
        p.addBatch();
        p.executeBatch();

        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.operation()).isEqualTo(Operation.BATCH);
        assertThat(entry.batchSize()).isEqualTo(2);
        assertThat(entry.affectedRows()).isEqualTo(3L);
    }

    @Test
    void recordsFailuresAndRethrows() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("update account set name = ?")).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("duplicate key"));

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        PreparedStatement p = traced.getConnection().prepareStatement("update account set name = ?");

        assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class).hasMessage("duplicate key");
        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.success()).isFalse();
        assertThat(entry.errorMessage()).isEqualTo("duplicate key");
    }

    @Test
    void delegatesUnwrapToTargetAndAvoidsDoubleWrapping() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        when(ds.unwrap(DataSource.class)).thenReturn(ds);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        assertThat(traced).isInstanceOf(SqlTracedDataSource.class);
        assertThat(traced.unwrap(DataSource.class)).isSameAs(ds);
        // Wrapping an already-traced data source returns it unchanged.
        assertThat(SqlTracingProxies.wrap(traced, recorder)).isSameAs(traced);
    }
}
