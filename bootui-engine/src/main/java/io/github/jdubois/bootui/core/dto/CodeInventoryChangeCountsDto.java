package io.github.jdubois.bootui.core.dto;

/**
 * What changed since the previous run of this application, compared without git from the class files' method hashes.
 *
 * @param previousRun whether a previous run was kept to compare with
 * @param note why there is no comparison, or what limits it, or {@code null}
 * @param changed methods whose code changed
 * @param added methods the previous run did not have
 * @param removed methods the previous run had that this one lacks, or {@code null} when that is unknown
 * @param executed changed and added methods that ran in this run
 * @param notExecuted changed and added tracked methods that did not run in this run
 * @param partial whether some classes could not be compared, as when a scan stopped at its limit
 * @param scanStatus the status of the scan the comparison comes from: {@code PENDING} or {@code RUNNING} before it is
 *     made, {@code FAILED} when it cannot be, else {@code COMPLETE} or {@code PARTIAL}
 */
public record CodeInventoryChangeCountsDto(
        boolean previousRun,
        String note,
        int changed,
        int added,
        Integer removed,
        int executed,
        int notExecuted,
        boolean partial,
        String scanStatus) {}
