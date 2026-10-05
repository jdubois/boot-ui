package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Side Effects row ({@code docs/PLAN-v2.md} §5.16): what a sensor saw, aggregated per attribution, kind,
 * normalized target, and call site. Targets never carry a value: a command's file name, never its arguments or
 * environment.
 *
 * @param scope how the row is attributed: {@value #ROUTE}, {@value #EXECUTION}, {@value #STARTUP}, {@value #THREAD},
 *     {@value #UNATTRIBUTED}, or {@value #OTHER}
 * @param attribution the route ({@code GET /reports}), {@code startup}, the thread family ({@code pool-{n}-thread-{n}}),
 *     or what the scope names otherwise
 * @param sensor the sensor's id, such as {@code processes}
 * @param kind what was done, such as {@code process}
 * @param target the normalized target, such as a command's file name
 * @param callSite the first application frame, else the first frame outside the JDK, as {@code Class#method}, or
 *     {@code null} when unknown
 * @param insideMethod the innermost application bean method open when it happened, from Code Paths, or {@code null}
 * @param count how many times it happened: for processes, how many starts were attempted
 * @param failed how many of them failed: for processes, starts that threw
 * @param completed for processes, how many of the started processes exited
 * @param nonZeroExits for processes, how many exited with a non-zero status
 * @param lastExitStatus for processes, the last exit status seen, or {@code null}
 * @param totalMillis the time they took: for processes, the started processes' lifetime until they exited
 * @param maxMillis the longest of them
 * @param firstSeen when it was first seen, in epoch milliseconds
 * @param lastSeen when it was last seen, in epoch milliseconds
 * @param exemplarRequestIds up to three request ids that did it, for Live Activity
 */
public record SideEffectsRowDto(
        String scope,
        String attribution,
        String sensor,
        String kind,
        String target,
        String callSite,
        String insideMethod,
        long count,
        long failed,
        long completed,
        long nonZeroExits,
        Integer lastExitStatus,
        long totalMillis,
        long maxMillis,
        long firstSeen,
        long lastSeen,
        List<String> exemplarRequestIds) {

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

    public SideEffectsRowDto {
        exemplarRequestIds = DtoCollections.immutableCopy(exemplarRequestIds);
    }
}
