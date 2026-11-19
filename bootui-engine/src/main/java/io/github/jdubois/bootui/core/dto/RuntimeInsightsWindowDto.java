package io.github.jdubois.bootui.core.dto;

/**
 * The events a Runtime Insights report covers ({@code docs/PLAN-v2.md} §5.5).
 *
 * @param runId the run
 * @param firstEventAt the oldest retained event, in epoch milliseconds, or {@code null}
 * @param lastEventAt the newest retained event, in epoch milliseconds, or {@code null}
 * @param retainedEvents the retained events the projection read
 * @param requests the completed HTTP exchanges among them. Zero is not proof nothing ran: scheduled runs, consumed
 *     messages, and evicted traffic are not counted
 * @param evictedEvents the events the journal evicted, which the projection cannot read
 * @param droppedEvents the events the journal dropped, which nothing can read
 * @param recordingClears recording clears during this journal's lifetime, or {@code -1} when unknown
 * @param captureInterrupted whether recording is paused or any source has a loss watermark, including a resumed pause
 */
public record RuntimeInsightsWindowDto(
        String runId,
        Long firstEventAt,
        Long lastEventAt,
        int retainedEvents,
        int requests,
        long evictedEvents,
        long droppedEvents,
        long recordingClears,
        boolean captureInterrupted) {

    public RuntimeInsightsWindowDto(
            String runId,
            Long firstEventAt,
            Long lastEventAt,
            int retainedEvents,
            int requests,
            long evictedEvents,
            long droppedEvents,
            long recordingClears) {
        this(
                runId,
                firstEventAt,
                lastEventAt,
                retainedEvents,
                requests,
                evictedEvents,
                droppedEvents,
                recordingClears,
                false);
    }

    public RuntimeInsightsWindowDto(
            String runId,
            Long firstEventAt,
            Long lastEventAt,
            int retainedEvents,
            int requests,
            long evictedEvents,
            long droppedEvents) {
        this(runId, firstEventAt, lastEventAt, retainedEvents, requests, evictedEvents, droppedEvents, -1, true);
    }
}
