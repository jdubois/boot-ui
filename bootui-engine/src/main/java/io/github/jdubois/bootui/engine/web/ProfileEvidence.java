package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The already-captured evidence an adapter hands to {@link ExecutionProfileAssembler}.
 *
 * <p>Every source is read through its own panel's service, so records arrive already masked, filtered
 * for BootUI's own traffic, and bounded exactly as that panel shows them. Reading evidence performs no
 * capture, network call, or mutation.</p>
 *
 * @param requests every captured HTTP exchange, each a potential anchor; the profiled request is found
 *     among them by id
 * @param sql captured SQL executions
 * @param exceptions exception groups with every retained occurrence
 * @param security captured security audit events
 * @param restCalls captured outbound REST client calls
 * @param cacheAccesses captured cache accesses
 * @param traces looks up the distributed trace for a trace id, returning {@code null} when none is
 *     retained; may be {@code null} when the Traces source is unavailable
 */
public record ProfileEvidence(
        List<HttpExchangeDto> requests,
        Source<SqlTraceEntryDto> sql,
        Source<ExceptionDetailDto> exceptions,
        Source<SecurityLogEventDto> security,
        Source<RestClientTraceEntryDto> restCalls,
        Source<CacheActivityEvent> cacheAccesses,
        Function<String, TraceDetailDto> traces) {

    public ProfileEvidence {
        requests = nonNull(requests);
        sql = sql == null ? Source.unavailable(null) : sql;
        exceptions = exceptions == null ? Source.unavailable(null) : exceptions;
        security = security == null ? Source.unavailable(null) : security;
        restCalls = restCalls == null ? Source.unavailable(null) : restCalls;
        cacheAccesses = cacheAccesses == null ? Source.unavailable(null) : cacheAccesses;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null
                ? List.of()
                : values.stream().filter(Objects::nonNull).toList();
    }

    /**
     * One evidence source: its records when it is enabled and capturing, or why it is not.
     *
     * @param available whether the source panel is enabled and capturing
     * @param unavailableReason why it is not, or {@code null}
     * @param records the captured records; empty when unavailable
     * @param <T> the record type
     */
    public record Source<T>(boolean available, String unavailableReason, List<T> records) {

        public Source {
            records = available ? nonNull(records) : List.of();
            unavailableReason = available ? null : unavailableReason;
        }

        /** An enabled, capturing source. */
        public static <T> Source<T> of(List<T> records) {
            return new Source<>(true, null, records);
        }

        /** A source that cannot contribute, with the reason shown in the profile. */
        public static <T> Source<T> unavailable(String reason) {
            return new Source<>(false, reason == null ? "This source is not available." : reason, List.of());
        }

        /** A source whose panel is disabled by {@code bootui.panels.<id>.enabled}. */
        public static <T> Source<T> panelDisabled(String panelTitle) {
            return unavailable("The " + panelTitle + " panel is disabled.");
        }

        /** A source whose panel is enabled but is not capturing on this application. */
        public static <T> Source<T> notCapturing(String panelTitle) {
            return unavailable(panelTitle + " is not capturing on this application.");
        }

        /**
         * The cache accesses a recorder retained, or why it cannot contribute: absent, disabled, or not yet
         * wrapping any cache manager, in which case no access could have been captured.
         */
        public static Source<CacheActivityEvent> cacheAccesses(CacheActivityRecorder recorder) {
            if (recorder == null) {
                return unavailable("Cache access capture is not available on this application.");
            }
            if (!recorder.isEnabled()) {
                return unavailable("Cache access capture is disabled (bootui.cache.activity-capture-enabled=false).");
            }
            if (!recorder.hasInstrumentedManager()) {
                return unavailable("No cache manager is instrumented for cache access capture on this application.");
            }
            return of(recorder.recentEvents());
        }
    }
}
