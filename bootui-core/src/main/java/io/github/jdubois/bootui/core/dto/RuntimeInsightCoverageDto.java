package io.github.jdubois.bootui.core.dto;

/**
 * How one journal source's retained events are linked ({@code docs/PLAN-v2.md} §5.5): by request id, by execution id
 * (a scheduled run or consumed message), or not at all, which is expected for background work.
 *
 * @param source the journal source, such as {@code sql}
 * @param events its retained events
 * @param byRequestId those linked to a request
 * @param byExecutionId those linked to an execution only
 * @param unlinked those linked to neither
 * @param dropped the events of this source the journal dropped in this run
 */
public record RuntimeInsightCoverageDto(
        String source, long events, long byRequestId, long byExecutionId, long unlinked, long dropped) {}
