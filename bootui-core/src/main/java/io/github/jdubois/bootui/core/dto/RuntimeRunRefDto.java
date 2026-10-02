package io.github.jdubois.bootui.core.dto;

/**
 * A run a comparison names ({@code docs/PLAN-v2.md} §5.8): the current run, or a run whose summary is kept.
 *
 * @param runId the run's id
 * @param ordinal its position among the runs of the BootUI instance that recorded it
 * @param startedAt when it started, in epoch milliseconds
 * @param endedAt when it ended, in epoch milliseconds, or {@code null} for the current run
 * @param requests the HTTP requests it recorded
 * @param source where it comes from: {@code CURRENT}, {@code MEMORY} for a run this JVM kept, or {@code BASELINE_FILE}
 */
public record RuntimeRunRefDto(String runId, int ordinal, long startedAt, Long endedAt, long requests, String source) {}
