package io.github.jdubois.bootui.engine.model;

/** What a node of the runtime model is ({@code docs/PLAN-v2.md} §5.4). */
public enum NodeType {
    /** An HTTP route, such as {@code GET /api/orders/{id}}. */
    ROUTE,
    /** A GraphQL operation, a route subdivided by its operation, such as {@code POST /graphql (query Products)}. */
    GRAPHQL_OPERATION,
    /** A scheduled job, by its task. */
    SCHEDULED_JOB,
    /** A message listener, by its broker and the destination it consumes. */
    LISTENER,
    /** A bean, by its name. */
    BEAN,
    /** A repository bean, by its name. */
    REPOSITORY,
    /** A database table. */
    TABLE,
    /** A cache, by its name. */
    CACHE,
    /** An outbound host, by its authority. */
    HOST,
    /** A topic or queue, by its broker and destination. */
    DESTINATION,
    /** An AI model, by its provider and name. */
    AI_MODEL,
    /** An exception group, by its group id. */
    EXCEPTION_GROUP;

    /** Whether nodes of this type are executions, which own the work recorded under them. */
    public boolean execution() {
        return this == ROUTE || this == GRAPHQL_OPERATION || this == SCHEDULED_JOB || this == LISTENER;
    }
}
