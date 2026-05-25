package io.github.bootui.autoconfigure.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.bootui.core.BootUiDtos.SqlRequestDto;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class RecordingDataSourceWrapperTests {

    @Test
    void recordsPreparedStatementExecution() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(statement.executeUpdate()).thenReturn(7);

        SqlRecorder recorder = new SqlRecorder(8);
        DataSource wrapped = RecordingDataSourceWrapper.wrap(delegate, "primary", recorder);

        try (Connection c = wrapped.getConnection()) {
            PreparedStatement ps = c.prepareStatement("select * from t where id = ?");
            ps.executeQuery();
            PreparedStatement ps2 = c.prepareStatement("update t set v = ? where id = ?");
            ps2.executeUpdate();
        }

        List<SqlRequestDto> snapshot = recorder.snapshot();
        assertThat(snapshot).hasSize(2);
        // newest first
        assertThat(snapshot.get(0).sql()).isEqualTo("update t set v = ? where id = ?");
        assertThat(snapshot.get(0).statementType()).isEqualTo("PREPARED");
        assertThat(snapshot.get(0).success()).isTrue();
        assertThat(snapshot.get(0).affectedRows()).isEqualTo(7);
        assertThat(snapshot.get(1).sql()).isEqualTo("select * from t where id = ?");
        assertThat(snapshot.get(1).success()).isTrue();
    }

    @Test
    void recordsPlainStatementExecution() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        SqlRecorder recorder = new SqlRecorder(8);
        DataSource wrapped = RecordingDataSourceWrapper.wrap(delegate, "ds", recorder);

        try (Connection c = wrapped.getConnection()) {
            Statement st = c.createStatement();
            st.executeQuery("select 1");
        }

        List<SqlRequestDto> snapshot = recorder.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).statementType()).isEqualTo("STATEMENT");
        assertThat(snapshot.get(0).sql()).isEqualTo("select 1");
        assertThat(snapshot.get(0).dataSource()).isEqualTo("ds");
        assertThat(snapshot.get(0).success()).isTrue();
    }

    @Test
    void recordsExecutionFailures() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("syntax error"));

        SqlRecorder recorder = new SqlRecorder(8);
        DataSource wrapped = RecordingDataSourceWrapper.wrap(delegate, "ds", recorder);

        try (Connection c = wrapped.getConnection()) {
            PreparedStatement ps = c.prepareStatement("select bogus");
            try {
                ps.executeQuery();
            } catch (SQLException expected) {
                // expected to propagate
            }
        }

        List<SqlRequestDto> snapshot = recorder.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).success()).isFalse();
        assertThat(snapshot.get(0).error()).contains("syntax error");
        assertThat(snapshot.get(0).sql()).isEqualTo("select bogus");
    }

    @Test
    void unwrapReturnsOriginalDataSource() throws Exception {
        DataSource delegate = mock(DataSource.class);
        when(delegate.isWrapperFor(DataSource.class)).thenReturn(true);

        SqlRecorder recorder = new SqlRecorder(2);
        DataSource wrapped = RecordingDataSourceWrapper.wrap(delegate, "ds", recorder);

        assertThat(wrapped).isInstanceOf(RecordingDataSourceWrapper.RecordingDataSource.class);
        assertThat(((RecordingDataSourceWrapper.RecordingDataSource) wrapped).bootUiDelegate()).isSameAs(delegate);
        assertThat(wrapped.isWrapperFor(DataSource.class)).isTrue();
    }

    @Test
    void wrappingIsIdempotent() {
        DataSource delegate = mock(DataSource.class);
        SqlRecorder recorder = new SqlRecorder(2);
        DataSource once = RecordingDataSourceWrapper.wrap(delegate, "ds", recorder);
        DataSource twice = RecordingDataSourceWrapper.wrap(once, "ds", recorder);
        assertThat(twice).isSameAs(once);
    }
}
