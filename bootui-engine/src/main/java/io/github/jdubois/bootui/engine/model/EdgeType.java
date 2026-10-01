package io.github.jdubois.bootui.engine.model;

/** What an edge of the runtime model says about its two nodes ({@code docs/PLAN-v2.md} §5.4). */
public enum EdgeType {
    /** A bean depends on another bean. */
    DEPENDS_ON,
    /** A route is handled by a bean. */
    HANDLED_BY,
    /** An execution read a table or a cache. */
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
    RAISES
}
