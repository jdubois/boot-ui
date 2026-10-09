package io.github.jdubois.bootui.core.dto;

/**
 * One metric that moved between the previous read and this one.
 *
 * <p>The comparison is session-scoped and in memory only: BootUI keeps the previous read of the current
 * process, never a persisted baseline. Restarting the application starts over, which is stated rather than
 * hidden.</p>
 *
 * @param direction {@code UP}, {@code DOWN} or {@code FLAT}
 */
public record PostgresChangeDto(String metric, String previous, String current, String direction) {}
