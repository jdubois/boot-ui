package io.github.jdubois.bootui.engine.postgres;

/**
 * One comparable number retained from a read so the next read can say what changed.
 *
 * <p>Only the rendered form reaches the browser; the raw value exists so the direction of a change is
 * computed rather than guessed from formatted text.</p>
 */
record PostgresMetric(String name, Double value, String rendered) {}
