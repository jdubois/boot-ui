package io.github.jdubois.bootui.engine.resources;

import java.util.Objects;

/**
 * The collections of one pause collector that completed while a request ran: every id after {@code afterId}, up to and
 * including {@code lastId}. A collection's id is the collector's collection count once it completes, so the range joins
 * the {@code gc} source's events by id, whenever their notifications arrive ({@code docs/PLAN-v2.md} §5.11).
 *
 * @param collector the collector's name, such as {@code G1 Young Generation}
 * @param afterId the collector's collection count when the request's work started on a thread
 * @param lastId the collector's collection count when it stopped
 */
public record GcPauseRange(String collector, long afterId, long lastId) {

    public GcPauseRange {
        Objects.requireNonNull(collector, "collector must not be null");
        if (lastId <= afterId) {
            throw new IllegalArgumentException("A range names at least one collection");
        }
    }

    /** How many collections the range names. */
    public long count() {
        return lastId - afterId;
    }

    /** Whether {@code collector}'s collection {@code id} completed during the range. */
    public boolean contains(String collector, long id) {
        return this.collector.equals(collector) && id > afterId && id <= lastId;
    }
}
