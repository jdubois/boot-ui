package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * What a restart cost compared with the previous restart ({@code docs/PLAN-v2.md} §5.8): the time to ready and the
 * beans whose initialization moved. A restart is never compared with a cold start.
 *
 * @param status {@code COMPARED} or {@code UNAVAILABLE}
 * @param reason why it is unavailable, or {@code null}
 * @param readyMsBefore the previous run's time to ready, in milliseconds, or {@code null}
 * @param readyMsAfter the current run's time to ready, in milliseconds, or {@code null}
 * @param beans the beans whose initialization moved by at least 200 ms and 50 %, most moved first
 */
public record RuntimeRestartCostDto(
        String status, String reason, Double readyMsBefore, Double readyMsAfter, List<RuntimeRunChangeDto> beans) {

    public RuntimeRestartCostDto {
        beans = DtoCollections.immutableCopy(beans);
    }
}
