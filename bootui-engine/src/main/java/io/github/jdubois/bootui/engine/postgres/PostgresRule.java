package io.github.jdubois.bootui.engine.postgres;

/**
 * One deterministic check over the rows a read collected.
 *
 * <p>A rule never opens a connection, never runs a query, and never reasons about anything its section did
 * not read: the service only evaluates a rule whose section is {@code AVAILABLE}, so a blocked collector
 * yields a skipped section rather than a rule that silently passes.</p>
 */
interface PostgresRule {

    PostgresRuleDefinition definition();

    /** The match, or {@code null} when the rule did not fire. */
    PostgresRuleMatch evaluate(PostgresDatabaseData data);
}
