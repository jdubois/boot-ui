package io.github.jdubois.bootui.core.dto;

/**
 * A collection of a pause collector that completed while a request ran, joined to it by collector and id
 * ({@code docs/PLAN-v2.md} §5.11). It completed during the request; it is never reported as its cause.
 *
 * @param collector the collector, such as {@code G1 Young Generation}
 * @param gcId the collection's id
 * @param retained whether the journal still retains its {@code GC} event, which gives the fields below
 * @param offsetMillis when it started, from the request's start, or {@code null}
 * @param pauseMillis its duration in whole milliseconds, as the JVM reports it, or {@code null}
 * @param cause the JVM's cause, such as {@code G1 Evacuation Pause}, or {@code null}
 */
public record RequestGcPauseDto(
        String collector, long gcId, boolean retained, Long offsetMillis, Long pauseMillis, String cause) {}
