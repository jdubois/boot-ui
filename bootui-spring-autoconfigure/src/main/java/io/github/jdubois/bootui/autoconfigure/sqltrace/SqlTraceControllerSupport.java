package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared read/clear/recording business logic for the BootUI SQL Trace panel, used by both the
 * servlet {@code SqlTraceController} and the WebFlux {@code ReactiveSqlTraceController}. Both
 * bindings expose the identical REST contract over the same framework-neutral
 * {@link SqlTraceRecorder}, and none of this logic touches a servlet or reactive request/response
 * type, so it is extracted once here rather than duplicated per transport. The transport-specific
 * pieces (the {@code @RestController} wiring itself and the SSE {@code /stream} endpoint) stay in
 * each controller.
 */
public final class SqlTraceControllerSupport {

    private static final String NOT_CONFIGURED = "SQL tracing is not configured";

    private SqlTraceControllerSupport() {}

    public static SqlTraceReport trace(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            BootUiExposure exposure,
            ObjectProvider<DataSource> dataSourceProvider) {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        return report(recorder, exposure, dataSourceProvider);
    }

    public static SqlTraceReport clear(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            BootUiExposure exposure,
            ObjectProvider<DataSource> dataSourceProvider) {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        recorder.clear();
        return report(recorder, exposure, dataSourceProvider);
    }

    public static SqlTraceReport recording(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            BootUiExposure exposure,
            ObjectProvider<DataSource> dataSourceProvider,
            SqlTraceRecordingRequest request) {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report(recorder, exposure, dataSourceProvider);
    }

    private static SqlTraceReport report(
            SqlTraceRecorder recorder, BootUiExposure exposure, ObjectProvider<DataSource> dataSourceProvider) {
        if (!recorder.hasWrappedDataSource()) {
            return SqlTraceReport.unavailable(unavailableReason(recorder, dataSourceProvider));
        }
        boolean exposeParameters =
                recorder.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return recorder.report(exposeParameters);
    }

    private static String unavailableReason(SqlTraceRecorder recorder, ObjectProvider<DataSource> dataSourceProvider) {
        if (!recorder.isEnabled()) {
            return "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile).";
        }
        if (dataSourceProvider.getIfAvailable() == null) {
            return "No DataSource bean is available";
        }
        return "No DataSource has been wrapped for tracing yet.";
    }
}
