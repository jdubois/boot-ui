package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One handler of application code and what became of the exceptions it caught, as the BootUI agent's
 * {@code caught-exceptions} sensor observed them ({@code docs/PLAN-v2.md} M5-6). Occurrences are grouped by route,
 * handler, and caught class; each is counted under exactly one outcome.
 *
 * <p>Outcomes: {@code rethrown}, it left the method by a throw, as is or as a cause; {@code replaced}, the handler threw
 * another exception without it; {@code reported}, it reached the framework's error handling; {@code logged}, it was
 * logged at {@code WARN} or above, or a {@code WARN} was logged on the catching thread right after; {@code handedOn},
 * the handler passed it on as an error value (a failed future, an error signal); {@code reinterrupted}, the handler
 * restored the thread's interrupt; {@code retried}, a later attempt at the same handler in the same request was
 * rethrown, reported, or logged; {@code notRethrownOrLogged}, none of these was seen while the evidence was complete;
 * {@code unknown}, the evidence was incomplete ({@code unknownReason} names the most frequent reason); and
 * {@code pending}, its request has not settled yet. {@code counted} occurrences were only counted, past the agent's
 * per-thread bound, and are resolved under no outcome.</p>
 *
 * <p>{@code finding} is set when occurrences not rethrown or logged were seen in at least three requests, or in one for
 * an SQL, I/O, or data-access exception. It states a fact, never a verdict: the exception was not seen rethrown or
 * logged at {@code WARN} or above. No row carries an exception's message, stack trace, or identity.</p>
 *
 * @param id a stable key of the row's grouping
 * @param route the route template of the requests that caught it, or {@code null} when not caught under a request or
 *     when HTTP Exchanges is hidden
 * @param ownerKind {@code request}, {@code task}, {@code execution}, or {@code none}
 * @param siteClass the handler's class
 * @param method the handler's method name
 * @param line the handler's line, or 0 when unknown
 * @param declaredTypes the types the handler declares
 * @param exceptionClass the caught exception's class
 * @param family {@code sql}, {@code io}, {@code data-access}, or {@code null}
 * @param shapes what the handler's own code shows: {@code discards}, {@code prints-stack-trace}, {@code reinterrupts},
 *     {@code passes-as-value}, {@code throws-new}
 * @param exemplarRequestId one request that caught it, or {@code null} when HTTP Exchanges is hidden
 * @param firstSeen the first occurrence, in epoch milliseconds
 * @param lastSeen the last occurrence, in epoch milliseconds
 */
public record CaughtExceptionRowDto(
        String id,
        String route,
        String ownerKind,
        String siteClass,
        String method,
        int line,
        List<String> declaredTypes,
        String exceptionClass,
        String family,
        long occurrences,
        int requests,
        long rethrown,
        long replaced,
        long reported,
        long logged,
        long handedOn,
        long reinterrupted,
        long retried,
        long notRethrownOrLogged,
        long unknown,
        long pending,
        long counted,
        String unknownReason,
        List<String> shapes,
        boolean finding,
        String exemplarRequestId,
        long firstSeen,
        long lastSeen) {

    public CaughtExceptionRowDto {
        declaredTypes = DtoCollections.immutableCopy(declaredTypes);
        shapes = DtoCollections.immutableCopy(shapes);
    }
}
