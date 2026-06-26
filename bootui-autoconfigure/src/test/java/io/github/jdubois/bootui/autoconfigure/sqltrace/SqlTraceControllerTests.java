package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SqlTraceControllerTests {

    private SqlTraceRecorder recorder(boolean enabled, boolean captureParameters) {
        return new SqlTraceRecorder(enabled, true, captureParameters, 10, 100, 2000, 200, 5);
    }

    private SqlTraceRecorder wrappedRecorder(boolean captureParameters) {
        SqlTraceRecorder recorder = recorder(true, captureParameters);
        recorder.registerDataSource("dataSource");
        return recorder;
    }

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

    private SqlTraceController controller(SqlTraceRecorder recorder, DataSource dataSource, ValueExposure exposure) {
        return new SqlTraceController(recorderProvider(recorder), dataSourceProvider(dataSource), exposure(exposure));
    }

    @Test
    void reportsUnavailableWhenNoDataSource() {
        SqlTraceController controller = controller(recorder(true, true), null, ValueExposure.MASKED);

        SqlTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("No DataSource bean is available");
    }

    @Test
    void reportsDisabledReasonWhenTracingOff() {
        SqlTraceController controller =
                controller(recorder(false, false), mock(DataSource.class), ValueExposure.MASKED);

        SqlTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("disabled");
    }

    @Test
    void reportsCapturedStatements() {
        SqlTraceRecorder recorder = wrappedRecorder(true);
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select ?",
                List.of("'x'"),
                150,
                true,
                null,
                null,
                0,
                "conn-1",
                "main");
        SqlTraceController controller = controller(recorder, mock(DataSource.class), ValueExposure.MASKED);

        SqlTraceReport report = controller.trace();
        assertThat(report.available()).isTrue();
        assertThat(report.capturing()).isTrue();
        assertThat(report.captureParameters()).isTrue();
        assertThat(report.dataSources()).containsExactly("dataSource");
        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).slow()).isTrue();
        assertThat(report.entries().get(0).category()).isEqualTo("SELECT");
        assertThat(report.entries().get(0).parameters()).containsExactly("'x'");
        assertThat(report.topStatements()).hasSize(1);
        assertThat(report.stats().totalQueries()).isEqualTo(1);
        assertThat(report.warnings()).anyMatch(w -> w.contains("clear text"));
    }

    @Test
    void suppressesParametersUnderMetadataOnlyExposure() {
        SqlTraceRecorder recorder = wrappedRecorder(true);
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select ?",
                List.of("'x'"),
                5,
                true,
                null,
                null,
                0,
                "conn-1",
                "main");
        SqlTraceController controller = controller(recorder, mock(DataSource.class), ValueExposure.METADATA_ONLY);

        SqlTraceReport report = controller.trace();
        assertThat(report.entries().get(0).parameters()).isEmpty();
        assertThat(report.warnings()).noneMatch(w -> w.contains("clear text"));
    }

    @Test
    void clearEmptiesTheBuffer() {
        SqlTraceRecorder recorder = wrappedRecorder(false);
        recorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 1",
                List.of(),
                5,
                true,
                null,
                null,
                0,
                "conn-1",
                "main");
        SqlTraceController controller = controller(recorder, mock(DataSource.class), ValueExposure.MASKED);

        SqlTraceReport report = controller.clear();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void recordingTogglePausesAndResumes() {
        SqlTraceRecorder recorder = wrappedRecorder(false);
        SqlTraceController controller = controller(recorder, mock(DataSource.class), ValueExposure.MASKED);

        SqlTraceReport paused = controller.recording(new SqlTraceRecordingRequest(false));
        assertThat(paused.capturing()).isFalse();
        assertThat(paused.warnings()).anyMatch(w -> w.contains("paused"));

        SqlTraceReport toggled = controller.recording(null);
        assertThat(toggled.capturing()).isTrue();
    }
}
