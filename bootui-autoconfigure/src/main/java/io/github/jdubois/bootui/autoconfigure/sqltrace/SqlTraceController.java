package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.CapturedStatement;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-mostly endpoint backing the SQL Trace panel.
 *
 * <p>Returns the SQL captured by BootUI's hand-written JDBC tracing proxy and
 * exposes state-changing {@code clear} and {@code recording} (pause/resume)
 * actions (gated by the panel access filter when the panel is read-only).
 * Parameter bindings are only surfaced when capture is enabled and value
 * exposure is not metadata-only.</p>
 */
@RestController
@RequestMapping("/bootui/api/sql-trace")
public class SqlTraceController {

    private static final String NOT_CONFIGURED = "SQL tracing is not configured";

    private final ObjectProvider<SqlTraceRecorder> recorderProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final BootUiExposure exposure;
    private final BootUiChangeStream changeStream;

    public SqlTraceController(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiExposure exposure) {
        this.recorderProvider = recorderProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.exposure = exposure;
        this.changeStream = new BootUiChangeStream("sql-trace");
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.subscribe(changeStream::signal);
        }
    }

    @GetMapping
    public SqlTraceReport trace() {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        return report(recorder);
    }

    @PostMapping("/clear")
    public SqlTraceReport clear() {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        recorder.clear();
        return report(recorder);
    }

    @PostMapping("/recording")
    public SqlTraceReport recording(@RequestBody(required = false) SqlTraceRecordingRequest request) {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report(recorder);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a statement is captured, the buffer is
     * cleared, or recording is paused/resumed, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }

    private SqlTraceReport report(SqlTraceRecorder recorder) {
        if (!recorder.hasWrappedDataSource()) {
            return SqlTraceReport.unavailable(unavailableReason(recorder));
        }
        boolean exposeParameters =
                recorder.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        List<SqlTraceEntryDto> entries = recorder.recent().stream()
                .map(entry -> toDto(entry, recorder, exposeParameters))
                .toList();
        return new SqlTraceReport(
                true,
                null,
                recorder.isRecording(),
                recorder.isCaptureParameters(),
                recorder.getMaxEntries(),
                recorder.totalCaptured(),
                recorder.getSlowQueryThresholdMillis(),
                recorder.dataSourceNames(),
                recorder.stats(),
                entries,
                recorder.topStatements(),
                warnings(recorder, exposeParameters));
    }

    private String unavailableReason(SqlTraceRecorder recorder) {
        if (!recorder.isEnabled()) {
            return "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile).";
        }
        if (dataSourceProvider.getIfAvailable() == null) {
            return "No DataSource bean is available";
        }
        return "No DataSource has been wrapped for tracing yet.";
    }

    private List<String> warnings(SqlTraceRecorder recorder, boolean exposeParameters) {
        List<String> warnings = new ArrayList<>();
        if (!recorder.isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new queries.");
        }
        if (exposeParameters) {
            warnings.add("Bound parameter values are captured in clear text. "
                    + "Set bootui.sql-trace.capture-parameters=false to hide them.");
        }
        if (recorder.evicted() > 0) {
            warnings.add(
                    "Older queries were dropped; the buffer keeps the most recent " + recorder.getMaxEntries() + ".");
        }
        return warnings;
    }

    private SqlTraceEntryDto toDto(CapturedStatement entry, SqlTraceRecorder recorder, boolean exposeParameters) {
        return new SqlTraceEntryDto(
                entry.id(),
                entry.timestamp(),
                entry.sql(),
                entry.statementType().name(),
                entry.category().name(),
                entry.durationMillis(),
                entry.success(),
                entry.errorMessage(),
                entry.affectedRows(),
                entry.batchSize(),
                entry.connectionId(),
                entry.thread(),
                recorder.isSlow(entry.durationMillis()),
                exposeParameters ? entry.parameters() : List.of(),
                entry.traceId());
    }
}
