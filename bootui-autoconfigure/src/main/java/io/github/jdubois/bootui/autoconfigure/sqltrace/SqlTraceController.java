package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.CapturedStatement;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-mostly endpoint backing the SQL Trace panel.
 *
 * <p>Returns the SQL captured by BootUI's hand-written JDBC tracing proxy and
 * exposes a state-changing {@code clear} action (gated by the panel access
 * filter when the panel is read-only). Parameter bindings are only surfaced when
 * capture is enabled and value exposure is not metadata-only.</p>
 */
@RestController
@RequestMapping("/bootui/api/sql-trace")
public class SqlTraceController {

    private static final String NO_DATA_SOURCE = "No DataSource bean is available";

    private final ObjectProvider<SqlTraceRecorder> recorderProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final BootUiExposure exposure;

    public SqlTraceController(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiExposure exposure) {
        this.recorderProvider = recorderProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.exposure = exposure;
    }

    @GetMapping
    public SqlTraceReport trace() {
        if (dataSourceProvider.getIfAvailable() == null) {
            return SqlTraceReport.unavailable(NO_DATA_SOURCE);
        }
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable("SQL tracing is not configured");
        }
        return report(recorder);
    }

    @PostMapping("/clear")
    public SqlTraceReport clear() {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceReport.unavailable("SQL tracing is not configured");
        }
        recorder.clear();
        return report(recorder);
    }

    private SqlTraceReport report(SqlTraceRecorder recorder) {
        boolean exposeParameters =
                recorder.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        List<SqlTraceEntryDto> entries = recorder.recent().stream()
                .map(entry -> toDto(entry, recorder, exposeParameters))
                .toList();
        SqlTraceStatsDto stats = recorder.stats();
        return new SqlTraceReport(
                true,
                recorder.isEnabled(),
                recorder.isCaptureParameters(),
                recorder.getMaxEntries(),
                recorder.totalCaptured(),
                recorder.getSlowQueryThresholdMillis(),
                stats,
                entries,
                null);
    }

    private SqlTraceEntryDto toDto(CapturedStatement entry, SqlTraceRecorder recorder, boolean exposeParameters) {
        return new SqlTraceEntryDto(
                entry.id(),
                entry.timestamp(),
                entry.sql(),
                entry.statementType().name(),
                entry.operation().name(),
                entry.durationMillis(),
                entry.success(),
                entry.errorMessage(),
                entry.affectedRows(),
                entry.batchSize(),
                entry.connectionId(),
                recorder.isSlow(entry.durationMillis()),
                exposeParameters ? entry.parameters() : List.of());
    }
}
