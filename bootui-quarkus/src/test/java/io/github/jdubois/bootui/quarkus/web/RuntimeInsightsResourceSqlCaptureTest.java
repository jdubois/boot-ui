package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

class RuntimeInsightsResourceSqlCaptureTest {

    @Test
    @SuppressWarnings("unchecked")
    void withoutAJdbcDataSourceTheApplicationsSqlIsNotRecorded() {
        Instance<SqlTraceRecorder> absent = mock(Instance.class);
        when(absent.isResolvable()).thenReturn(false);

        assertThat(RuntimeInsightsResource.sqlCapture(absent))
                .as("Hibernate Reactive or a reactive SQL client, with no Agroal data source")
                .isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
        assertThat(RuntimeInsightsResource.sqlCapture(null)).isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgroalDataSourceRecordsItsSqlBeforeAnyStatementRan() {
        Instance<SqlTraceRecorder> recorder = mock(Instance.class);
        when(recorder.isResolvable()).thenReturn(true);
        when(recorder.get()).thenReturn(new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5));
        Instance<SqlTraceRecorder> disabled = mock(Instance.class);
        when(disabled.isResolvable()).thenReturn(true);
        when(disabled.get()).thenReturn(new SqlTraceRecorder(false, true, false, false, 8, 100, 2000, 200, 5));

        // Hibernate ORM's inspector registers its data source on the first statement, so none is required yet.
        assertThat(RuntimeInsightsResource.sqlCapture(recorder)).isEqualTo(SqlCapture.capturing());
        assertThat(RuntimeInsightsResource.sqlCapture(disabled)).isEqualTo(SqlCapture.notRecorded(SqlCapture.DISABLED));
    }
}
