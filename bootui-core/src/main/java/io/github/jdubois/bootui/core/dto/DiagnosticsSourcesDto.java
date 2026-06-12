package io.github.jdubois.bootui.core.dto;

/**
 * Which underlying signal sources are currently contributing data to the Diagnostics dashboard, so
 * the UI can explain what is (and is not) being correlated.
 *
 * @param httpExchanges whether the HTTP exchanges repository is available
 * @param sqlTrace whether SQL tracing is active
 * @param exceptions whether exception capture is active
 * @param securityLogs whether a security audit event repository is available
 * @param traces whether trace/span telemetry is available
 * @param logTail whether the log buffer is available
 */
public record DiagnosticsSourcesDto(
        boolean httpExchanges,
        boolean sqlTrace,
        boolean exceptions,
        boolean securityLogs,
        boolean traces,
        boolean logTail) {}
