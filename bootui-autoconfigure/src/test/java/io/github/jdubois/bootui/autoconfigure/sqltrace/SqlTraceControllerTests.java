package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Operation;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SqlTraceControllerTests {

    @SuppressWarnings("unchecked")
    private ObjectProvider<SqlTraceRecorder> recorderProvider(SqlTraceRecorder recorder) {
        ObjectProvider<SqlTraceRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DataSource> dataSourceProvider(DataSource dataSource) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return provider;
    }

    private BootUiExposure exposure(ValueExposure valueExposure) {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(valueExposure);
        return new BootUiExposure(properties);
    }

    @Test
    void reportsUnavailableWhenNoDataSource() {
        SqlTraceController controller = new SqlTraceController(
                recorderProvider(new SqlTraceRecorder(true, true, 10, 100, 2000, 200)),
                dataSourceProvider(null),
                exposure(ValueExposure.MASKED));

        SqlTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("No DataSource bean is available");
    }

    @Test
    void reportsCapturedStatements() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, 10, 100, 2000, 200);
        recorder.record(
                StatementType.PREPARED, Operation.QUERY, "select ?", List.of("'x'"), 150, true, null, null, 0, "conn-1");
        SqlTraceController controller = new SqlTraceController(
                recorderProvider(recorder), dataSourceProvider(mock(DataSource.class)), exposure(ValueExposure.MASKED));

        SqlTraceReport report = controller.trace();
        assertThat(report.available()).isTrue();
        assertThat(report.capturing()).isTrue();
        assertThat(report.captureParameters()).isTrue();
        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).slow()).isTrue();
        assertThat(report.entries().get(0).parameters()).containsExactly("'x'");
        assertThat(report.stats().totalQueries()).isEqualTo(1);
    }

    @Test
    void suppressesParametersUnderMetadataOnlyExposure() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, 10, 100, 2000, 200);
        recorder.record(
                StatementType.PREPARED, Operation.QUERY, "select ?", List.of("'x'"), 5, true, null, null, 0, "conn-1");
        SqlTraceController controller = new SqlTraceController(
                recorderProvider(recorder),
                dataSourceProvider(mock(DataSource.class)),
                exposure(ValueExposure.METADATA_ONLY));

        SqlTraceReport report = controller.trace();
        assertThat(report.entries().get(0).parameters()).isEmpty();
    }

    @Test
    void clearEmptiesTheBuffer() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, false, 10, 100, 2000, 200);
        recorder.record(
                StatementType.STATEMENT, Operation.QUERY, "select 1", List.of(), 5, true, null, null, 0, "conn-1");
        SqlTraceController controller = new SqlTraceController(
                recorderProvider(recorder), dataSourceProvider(mock(DataSource.class)), exposure(ValueExposure.MASKED));

        SqlTraceReport report = controller.clear();
        assertThat(report.entries()).isEmpty();
    }
}
