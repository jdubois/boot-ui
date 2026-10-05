package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Side Effects row ({@code docs/PLAN-v2.md} §5.16): what a sensor saw, aggregated per attribution, kind,
 * normalized target, and call site. Targets never carry a value: a command's file name, never its arguments or
 * environment; a host and port, never a byte sent or received; a file's path pattern, never its contents; a variable's or
 * a property's name, never its value.
 *
 * @param scope how the row is attributed: {@value #ROUTE}, {@value #EXECUTION}, {@value #STARTUP}, {@value #THREAD},
 *     {@value #UNATTRIBUTED}, or {@value #OTHER}
 * @param attribution the route ({@code GET /reports}), {@code startup}, the thread family ({@code pool-{n}-thread-{n}}),
 *     or what the scope names otherwise
 * @param sensor the sensor's id, such as {@code processes} or {@code blocking}
 * @param kind what was done: {@code process}; for network {@code connect}, {@code datagram}, or {@code lookup}; for
 *     files {@code read}, {@code write}, {@code delete}, {@code move from}, {@code move to}, {@code copy from}, or
 *     {@code copy to}; for environment {@code environment variable} or {@code system property}; for blocking
 *     {@code sleep}, {@code wait}, {@code park}, {@code network}, or {@code file}
 * @param target the normalized target: a command's file name, a {@code host:port}, a {@code unix:} path, or a
 *     looked-up host name, a file's path pattern ({@code ./reports/report-{n}.csv}, {@code $TMPDIR/…}, {@code ~/…}), a
 *     variable's or property's name, or for blocking the event loop's thread family ({@code reactor-http-nio-{n}})
 * @param callSite the first application frame, else the first frame outside the JDK, as {@code Class#method}, or
 *     {@code null} when unknown
 * @param insideMethod the innermost application bean method open when it happened, from Code Paths, or {@code null}
 * @param origin for files and environment, who did it: {@code application}, {@code library} (no application frame),
 *     {@code class-path}, {@code jdk}, {@code logging}, or {@code unknown}; the last three are grouped apart; {@code null}
 *     otherwise
 * @param location for files, where the file is: {@code working-directory}, {@code temporary-directory}, {@code home},
 *     {@code system}, {@code java-home}, or {@code elsewhere}; {@code null} otherwise
 * @param count how many times it happened: for processes, how many starts were attempted; for network, connects
 *     attempted, datagrams sent, or names the JVM resolved; for files, operations; for environment, first reads per
 *     request and thread
 * @param failed how many of them failed: for processes, starts that threw; for network, connects refused or failed,
 *     sends that threw, or names not resolved; for files, operations that threw; for blocking, calls interrupted or
 *     that threw
 * @param completed for processes, how many of the started processes exited; for network connects, how many were
 *     established
 * @param nonZeroExits for processes, how many exited with a non-zero status
 * @param lastExitStatus for processes, the last exit status seen, or {@code null}
 * @param totalMillis the time they took: for processes, the started processes' lifetime until they exited; for network,
 *     the connect time of the connects whose time is known, the send time, or the name service's resolution time; for
 *     blocking, how long they blocked the event loop
 * @param maxMillis the longest of them
 * @param firstSeen when it was first seen, in epoch milliseconds
 * @param lastSeen when it was last seen, in epoch milliseconds
 * @param exemplarRequestIds up to three request ids that did it, for Live Activity
 * @param client for network, the client recognized from the calling frames, such as {@code PostgreSQL JDBC} or {@code
 *     JDK HttpClient}, or {@code null} when none is recognized or for another sensor
 * @param capture for network connects and datagrams, whether a panel shows the work: {@value #CAPTURED}, {@value
 *     #NOT_CAPTURED} (no visible panel captured it: a hidden outbound call), or {@value #INFRASTRUCTURE} (a recognized
 *     infrastructure client, such as a DNS resolver or a telemetry exporter, which no panel is meant to show); {@code
 *     null} for lookups and other sensors
 * @param capturedBy the id of the panel that captured it, such as {@code rest-client-trace}, or {@code null}
 * @param parameter for security sinks, the name of the request parameter whose value reached the sink, as
 *     {@code @RequestParam}, a path variable, or the query names it, or {@code param#} and four hexadecimal digits when
 *     its name is not safe to show; never its value; {@code null} for other sensors
 * @param detail for security sinks, the fact the row states, worded as a fact and never as a vulnerability, such as
 *     "Request input reached this SQL text unchanged: the value of `name` appeared inside a literal. Check that it is
 *     bound as a parameter or escaped."; {@code null} for other sensors
 */
public record SideEffectsRowDto(
        String scope,
        String attribution,
        String sensor,
        String kind,
        String target,
        String callSite,
        String insideMethod,
        String origin,
        String location,
        long count,
        long failed,
        long completed,
        long nonZeroExits,
        Integer lastExitStatus,
        long totalMillis,
        long maxMillis,
        long firstSeen,
        long lastSeen,
        List<String> exemplarRequestIds,
        String client,
        String capture,
        String capturedBy,
        String parameter,
        String detail) {

    /** A row of a sensor other than security sinks: no parameter and no detail. */
    public SideEffectsRowDto(
            String scope,
            String attribution,
            String sensor,
            String kind,
            String target,
            String callSite,
            String insideMethod,
            String origin,
            String location,
            long count,
            long failed,
            long completed,
            long nonZeroExits,
            Integer lastExitStatus,
            long totalMillis,
            long maxMillis,
            long firstSeen,
            long lastSeen,
            List<String> exemplarRequestIds,
            String client,
            String capture,
            String capturedBy) {
        this(
                scope,
                attribution,
                sensor,
                kind,
                target,
                callSite,
                insideMethod,
                origin,
                location,
                count,
                failed,
                completed,
                nonZeroExits,
                lastExitStatus,
                totalMillis,
                maxMillis,
                firstSeen,
                lastSeen,
                exemplarRequestIds,
                client,
                capture,
                capturedBy,
                null,
                null);
    }

    /** Done while an HTTP request was handled, or by work it handed off. */
    public static final String ROUTE = "route";

    /** Done by an execution no request owns, such as a scheduled job. */
    public static final String EXECUTION = "execution";

    /** Done while the application started. */
    public static final String STARTUP = "startup";

    /** Done by a thread nothing else attributes it to, grouped by its thread family. */
    public static final String THREAD = "thread";

    /** Nothing attributes it. */
    public static final String UNATTRIBUTED = "unattributed";

    /** The rows a sensor could not keep apart once at its cap. */
    public static final String OTHER = "other";

    /** A panel shows the connection's work. */
    public static final String CAPTURED = "captured";

    /** No visible panel shows the connection's work: a hidden outbound call. */
    public static final String NOT_CAPTURED = "not-captured";

    /** A recognized infrastructure client, which no panel is meant to show. */
    public static final String INFRASTRUCTURE = "infrastructure";

    public SideEffectsRowDto {
        exemplarRequestIds = DtoCollections.immutableCopy(exemplarRequestIds);
    }
}
