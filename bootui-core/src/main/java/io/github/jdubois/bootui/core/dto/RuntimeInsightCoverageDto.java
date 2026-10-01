package io.github.jdubois.bootui.core.dto;

/**
 * How one journal source's retained events are linked ({@code docs/PLAN-v2.md} §5.5): by request id, by execution id
 * (a scheduled run or consumed message), by trace id, or not at all, which is expected for background work.
 *
 * @param source the journal source, such as {@code sql}
 * @param events its retained events
 * @param byRequestId those linked to a request by its id
 * @param byExecutionId those linked to an execution only
 * @param byTraceId those linked to a request by its trace id, such as AI spans exported after their request
 * @param unlinked those linked to none
 * @param dropped the events of this source the journal dropped in this run
 */
public record RuntimeInsightCoverageDto(
        String source,
        long events,
        long byRequestId,
        long byExecutionId,
        long byTraceId,
        long unlinked,
        long dropped) {}
