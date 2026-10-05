package io.github.jdubois.bootui.engine.model;

/** What an edge of the runtime model says about its two nodes ({@code docs/PLAN-v2.md} §5.4). */
public enum EdgeType {
    /** A bean depends on another bean. */
    DEPENDS_ON,
    /** A route is handled by a bean. */
    HANDLED_BY,
    /** An execution read a table, a cache, or, from Side Effects, an environment variable or a system property. */
    READS,
    /** An execution wrote a table or a cache. */
    WRITES,
    /** An execution called an outbound host or an AI model. */
    CALLS,
    /** An execution published to a topic or queue. */
    PUBLISHES,
    /** A listener consumed from a topic or queue. */
    CONSUMES,
    /** An execution raised an exception group. */
    RAISES,
    /**
     * A bean's method called another bean's, as the BootUI agent's code paths observed it in this run's route trees
     * ({@code docs/PLAN-v2.md} §5.14, M5-4c). Not part of change impact's code closure: a call observed in one run is
     * evidence of that run's paths, not of what a change can reach.
     */
    INVOKES,
    /**
     * A route, a scheduled job, or a bean opened connections to, or sent datagrams to, a host, as the BootUI agent's
     * {@code network} sensor observed it ({@code docs/PLAN-v2.md} §5.16, M5-5b), or an execution opened, deleted, moved,
     * or copied files of a path pattern, as its {@code files} sensor observed it (M5-5d). Not part of change impact's
     * closure.
     */
    OPENS
}
