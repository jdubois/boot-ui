package io.github.jdubois.bootui.engine.model;

/** Where an edge of the runtime model comes from ({@code docs/PLAN-v2.md} §5.4); never merged into one claim. */
public enum Provenance {
    /** Declared by the application's structure, such as a bean's dependencies or a route's handler. */
    DECLARED,
    /** Observed in an execution that owned the work, by its request or execution id. */
    OBSERVED,
    /** Inferred from shared access to a resource, such as two executions using one table. */
    INFERRED
}
