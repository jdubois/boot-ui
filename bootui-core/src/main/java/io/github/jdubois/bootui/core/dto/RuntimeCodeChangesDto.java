package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The code changes a run comparison leads with ({@code docs/PLAN-v2.md} §5.8, §5.17, M5-7a): the methods changed, added,
 * and removed since this application's previous run, which Code Inventory compares from the class files without git,
 * whether each ran in this run, and on which routes. Unavailable, with the reason, without the BootUI agent's inventory
 * sensor, and when the comparison is not with the previous run.
 *
 * @param available whether code changes can be listed for this comparison
 * @param unavailableReason why not, or {@code null}
 * @param counts how many methods changed, were added, were removed, and ran, or {@code null} when unavailable
 * @param methods the changed and added methods, not executed first, at most {@link RuntimeRunComparisonDto#MAX_ROWS}
 * @param methodsTotal how many there are
 * @param limitations what the code changes cannot see
 */
public record RuntimeCodeChangesDto(
        boolean available,
        String unavailableReason,
        CodeInventoryChangeCountsDto counts,
        List<RuntimeCodeChangeDto> methods,
        int methodsTotal,
        List<String> limitations) {

    public RuntimeCodeChangesDto {
        methods = DtoCollections.immutableCopy(methods);
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** Code changes are not listed, for {@code reason}. */
    public static RuntimeCodeChangesDto unavailable(String reason) {
        return new RuntimeCodeChangesDto(false, reason, null, List.of(), 0, List.of());
    }
}
