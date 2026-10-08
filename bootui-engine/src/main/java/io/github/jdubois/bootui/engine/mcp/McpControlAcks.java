package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.TracesReport;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The compact acknowledgement every capture-control tool answers with, on every stack: {@code pause_*}, {@code
 * resume_*} and {@code clear_*} for SQL Trace, Transactions and REST Client Trace, plus {@code clear_exceptions} and
 * {@code clear_traces}.
 *
 * <p>The browser's control endpoints keep returning the full panel report, which the page renders at once. An agent
 * only needs to know the action took effect, so it gets the panel's state after the action, in one shape for all
 * eleven tools, and reads the rows with the panel's {@code get_*} tool when it wants them:
 *
 * <ul>
 *   <li>{@code action}: {@code paused}, {@code resumed} or {@code cleared};
 *   <li>{@code available} and {@code unavailableReason}: whether the panel can capture at all;
 *   <li>{@code capturing}: whether new entries are being recorded after the action;
 *   <li>{@code retained}: the entries (exception groups for Exceptions) the panel still holds, {@code 0} after a clear;
 *   <li>{@code capacity}: the most entries the panel retains;
 *   <li>{@code totalCaptured}: the entries recorded since startup. It is a lifetime count, which a clear does not reset,
 *       and {@code null} for Exceptions and Traces, which keep none.
 * </ul>
 */
public final class McpControlAcks {

    /** The recording was paused. */
    public static final String PAUSED = "paused";

    /** The recording was resumed. */
    public static final String RESUMED = "resumed";

    /** The retained entries were dropped. */
    public static final String CLEARED = "cleared";

    private McpControlAcks() {}

    /** The acknowledgement for a SQL Trace control tool, from the report the panel returned after the action. */
    public static Map<String, Object> sqlTrace(String action, SqlTraceReport report) {
        return ack(
                action,
                report.available(),
                report.unavailableReason(),
                report.capturing(),
                report.entries().size(),
                report.bufferSize(),
                report.totalCaptured());
    }

    /** The acknowledgement for a Transactions control tool, from the report the panel returned after the action. */
    public static Map<String, Object> transactions(String action, TransactionReport report) {
        return ack(
                action,
                report.available(),
                report.unavailableReason(),
                report.capturing(),
                report.entries().size(),
                report.bufferSize(),
                report.totalCaptured());
    }

    /** The acknowledgement for a REST Client Trace control tool, from the report the panel returned after the action. */
    public static Map<String, Object> restClientTrace(String action, RestClientTraceReport report) {
        return ack(
                action,
                report.available(),
                report.unavailableReason(),
                report.capturing(),
                report.entries().size(),
                report.bufferSize(),
                report.totalCaptured());
    }

    /**
     * The acknowledgement for {@code clear_exceptions}, from the report read after the clear. Exceptions are always
     * captured while the panel is available, and {@code retained} counts exception groups.
     */
    public static Map<String, Object> exceptionsCleared(ExceptionsReport report) {
        return ack(
                CLEARED,
                report.available(),
                report.unavailableReason(),
                report.available(),
                report.groups().size(),
                report.maxGroups(),
                null);
    }

    /** The acknowledgement for {@code clear_traces}, from the report read after the clear. */
    public static Map<String, Object> tracesCleared(TracesReport report) {
        return ack(
                CLEARED,
                report.enabled(),
                report.enabled() ? null : "Trace capture is disabled by bootui.telemetry.enabled=false.",
                report.enabled(),
                report.retained(),
                report.capacity(),
                null);
    }

    private static Map<String, Object> ack(
            String action,
            boolean available,
            String unavailableReason,
            boolean capturing,
            int retained,
            int capacity,
            Long totalCaptured) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("action", action);
        ack.put("available", available);
        ack.put("unavailableReason", unavailableReason);
        ack.put("capturing", capturing);
        ack.put("retained", retained);
        ack.put("capacity", capacity);
        ack.put("totalCaptured", totalCaptured);
        return ack;
    }
}
