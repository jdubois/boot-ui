package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Code Inventory panel's summary ({@code docs/PLAN-v2.md} §5.15, M5-3): this run, how many of the application's
 * methods it executed, what changed since the previous run, and which dependencies loaded classes. Needs the BootUI
 * agent's {@code inventory} sensor; without it the report is unavailable with the Java Agent panel's reason.
 *
 * @param available whether the inventory sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param run this run's claim, or {@code null} when unavailable
 * @param scan the disk scan of the application's class files, or {@code null} when unavailable
 * @param methods the method counts, or {@code null} when unavailable
 * @param changes what changed since the previous run, or {@code null} when unavailable
 * @param dependencies the dependency counts, or {@code null} when unavailable
 * @param limitations what the counts cannot see
 * @param recordingClearedAt when <b>Clear recording</b> last dropped this run's first requests and routes, in epoch
 *     milliseconds, or {@code null}: a method whose first call is older executed before it, and may not have run since
 */
public record CodeInventoryReport(
        boolean available,
        String unavailableReason,
        CodeInventoryRunDto run,
        CodeInventoryScanDto scan,
        CodeInventoryMethodCountsDto methods,
        CodeInventoryChangeCountsDto changes,
        CodeInventoryDependencyCountsDto dependencies,
        List<String> limitations,
        Long recordingClearedAt) {

    public CodeInventoryReport {
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** The report without the sensor. */
    public static CodeInventoryReport unavailable(String reason) {
        return new CodeInventoryReport(false, reason, null, null, null, null, null, List.of(), null);
    }
}
