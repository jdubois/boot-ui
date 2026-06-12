package io.github.jdubois.bootui.autoconfigure.sql;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.SqlTraceActionResult;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceQueryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the SQL executions captured by {@link SqlTraceStore} and lets a trusted local session
 * pause/resume recording and clear the buffer.
 *
 * <p>State-changing endpoints are guarded upstream by the BootUI panel access filter, which blocks
 * them when the {@code sql-trace} panel is read-only.</p>
 */
@RestController
@RequestMapping("/bootui/api/sql-trace")
public class SqlTraceController {

    private static final String PROXY_DATA_SOURCE_CLASS = "net.ttddyy.dsproxy.support.ProxyDataSource";

    private final SqlTraceStore store;
    private final BootUiProperties properties;
    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public SqlTraceController(
            SqlTraceStore store, BootUiProperties properties, ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.store = store;
        this.properties = properties;
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @GetMapping
    public SqlTraceReport sqlTrace() {
        SqlTraceStore.Snapshot snapshot = store.snapshot();
        List<SqlTraceQueryDto> newestFirst = new ArrayList<>(snapshot.queries());
        Collections.reverse(newestFirst);
        SqlTraceStatsDto stats =
                SqlTraceStore.computeStats(snapshot.queries(), snapshot.captured(), snapshot.evicted());
        List<SqlTraceGroupDto> groups =
                SqlTraceStore.computeGroups(snapshot.queries(), SqlTraceStore.TOP_STATEMENTS_LIMIT);
        boolean available = store.hasWrappedDataSource();
        return new SqlTraceReport(
                available,
                available ? null : unavailableReason(),
                store.isRecording(),
                store.isCaptureParameters(),
                store.maxQueries(),
                store.slowQueryThresholdMillis(),
                store.dataSourceNames(),
                stats,
                newestFirst,
                groups,
                warnings(snapshot, available));
    }

    @PostMapping("/clear")
    public SqlTraceActionResult clear() {
        int cleared = store.clear();
        return new SqlTraceActionResult(store.isRecording(), cleared, "Cleared " + cleared + " recorded queries.");
    }

    @PostMapping("/recording")
    public SqlTraceActionResult recording(@RequestBody(required = false) SqlTraceRecordingRequest request) {
        boolean enabled = (request == null || request.enabled() == null) ? !store.isRecording() : request.enabled();
        store.setRecording(enabled);
        return new SqlTraceActionResult(
                enabled, 0, enabled ? "Recording resumed." : "Recording paused; existing queries are kept.");
    }

    private String unavailableReason() {
        if (!properties.getSqlTrace().isEnabled()) {
            return "SQL tracing is disabled (set bootui.sql-trace.enabled=true to enable it).";
        }
        if (!ClassUtils.isPresent(PROXY_DATA_SOURCE_CLASS, getClass().getClassLoader())) {
            return "datasource-proxy is not on the classpath.";
        }
        if (!dataSourcePresent()) {
            return "No DataSource beans are available.";
        }
        return "No DataSource has been wrapped for tracing yet.";
    }

    private boolean dataSourcePresent() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        return factory != null && factory.getBeanNamesForType(DataSource.class).length > 0;
    }

    private List<String> warnings(SqlTraceStore.Snapshot snapshot, boolean available) {
        List<String> warnings = new ArrayList<>();
        if (available && !store.isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new queries.");
        }
        if (store.isCaptureParameters()) {
            warnings.add("Bound parameter values are captured in clear text. "
                    + "Set bootui.sql-trace.capture-parameters=false to hide them.");
        }
        if (snapshot.evicted() > 0) {
            warnings.add("Older queries were dropped; the buffer keeps the most recent " + store.maxQueries() + ".");
        }
        return warnings;
    }
}
