package io.github.jdubois.bootui.engine.progress;

/**
 * One accepted progress report: {@code progress} is finite and strictly greater than every earlier event of the same
 * operation, as MCP requires.
 *
 * @param progress the units completed so far
 * @param total the total units when known and positive, otherwise {@code null}
 * @param message the phase label
 */
public record ProgressEvent(double progress, Double total, String message) {}
