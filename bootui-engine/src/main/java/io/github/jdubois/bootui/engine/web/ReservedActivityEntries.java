package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.function.Predicate;

/**
 * Whether a Live Activity entry stands for a record that one of BootUI's failure-preserving capture buffers flags for
 * its reserved share, so Live Activity persistence can recognize that record for as long as its buffer may keep it.
 *
 * <p>Each rule is the buffer's own, applied to the fields the entry carries:</p>
 *
 * <ul>
 *   <li>{@code REQUEST}: {@link RequestSlowThreshold#isFailedOrSlow}, the rule of every HTTP exchange buffer, on the
 *       entry's status and duration with the same request slow threshold. A slow {@code 4xx} request is reserved even
 *       though its severity is {@code WARN}, and a threshold of {@code 0} leaves only {@code 5xx} responses
 *       reserved.</li>
 *   <li>{@code SQL}: {@link SqlTraceRecorder#isFailedOrSlow}. The entry is {@code ERROR} exactly when the statement
 *       failed and {@code SLOW} exactly when a successful statement reached SQL Trace's slow threshold.</li>
 *   <li>{@code REST_CLIENT}: {@link RestClientTraceRecorder#isFailedOrSlow} on the entry's status. The entry is
 *       {@code ERROR} when the call failed (or was answered {@code 5xx}) and {@code SLOW} exactly when a call that
 *       neither failed nor was answered {@code 4xx}/{@code 5xx} reached REST Client's slow threshold.</li>
 * </ul>
 *
 * <p>A statement or call that is slow and also failed or answered with an error surfaces as {@code ERROR} or
 * {@code WARN}, which its rule reserves anyway, so SQL and REST need no slow threshold here. No other entry type comes
 * from a buffer with a reserved share, so the rule reserves none of them; the capture coordinator remembers their
 * failures by severity instead.</p>
 *
 * <p>The rule follows a buffer's classification, not its configured share: a record flagged by a buffer whose reserved
 * share is {@code 0}, or by an application-managed Spring exchange repository, is still reported as reserved. The
 * coordinator remembers each entry type in its own window, so such a record can only displace records of its own
 * type, which that buffer then keeps no longer than routine ones.</p>
 */
public final class ReservedActivityEntries implements Predicate<ActivityEntryDto> {

    private static final String TYPE_REQUEST = "REQUEST";
    private static final String TYPE_SQL = "SQL";
    private static final String TYPE_REST_CLIENT = "REST_CLIENT";
    private static final String SEVERITY_SLOW = "SLOW";
    private static final String SEVERITY_ERROR = "ERROR";

    private final long requestSlowThresholdMillis;

    /**
     * @param requestSlowThresholdMillis {@code bootui.activity.request-slow-threshold-ms}, exactly as the HTTP exchange
     *     buffer applies it; {@code 0} disables slow classification
     */
    public ReservedActivityEntries(long requestSlowThresholdMillis) {
        this.requestSlowThresholdMillis = Math.max(0L, requestSlowThresholdMillis);
    }

    /** The request slow threshold this rule classifies {@code REQUEST} entries with. */
    public long requestSlowThresholdMillis() {
        return requestSlowThresholdMillis;
    }

    @Override
    public boolean test(ActivityEntryDto entry) {
        if (entry == null || entry.type() == null) {
            return false;
        }
        return switch (entry.type()) {
            case TYPE_REQUEST ->
                RequestSlowThreshold.isFailedOrSlow(
                        entry.status() == null ? 0 : entry.status(), entry.durationMs(), requestSlowThresholdMillis);
            case TYPE_SQL ->
                SqlTraceRecorder.isFailedOrSlow(
                        !SEVERITY_ERROR.equals(entry.severity()), SEVERITY_SLOW.equals(entry.severity()));
            case TYPE_REST_CLIENT ->
                RestClientTraceRecorder.isFailedOrSlow(
                        !SEVERITY_ERROR.equals(entry.severity()),
                        entry.status(),
                        SEVERITY_SLOW.equals(entry.severity()));
            default -> false;
        };
    }
}
