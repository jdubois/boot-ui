package io.github.jdubois.bootui.core.dto;

/**
 * Database work that was deliberately <em>not</em> attributed to a route.
 *
 * <p>BootUI keeps these executions visible instead of forcing them onto a plausible-looking request:
 * background jobs, startup work and schema migrations legitimately have no inbound request, and
 * concurrent identical requests can make an attribution genuinely undecidable. Hiding either case would
 * silently distort every share and total on the panel.</p>
 *
 * @param executions retained executions in this bucket
 * @param totalDurationMillis summed duration of those executions, in fractional milliseconds
 * @param errorCount executions in this bucket that failed
 * @param shareOfRetainedTimePercent this bucket's share of the window's total database time, 0-100
 * @param reason plain-language explanation of why these executions are in this bucket
 */
public record SqlAttributionBucketDto(
        long executions,
        double totalDurationMillis,
        long errorCount,
        double shareOfRetainedTimePercent,
        String reason) {

    public static SqlAttributionBucketDto empty(String reason) {
        return new SqlAttributionBucketDto(0, 0, 0, 0, reason);
    }
}
