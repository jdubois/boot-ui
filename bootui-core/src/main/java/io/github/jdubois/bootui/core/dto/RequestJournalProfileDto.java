package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One request as the runtime journal recorded it ({@code docs/PLAN-v2.md} §5.3, §5.11), a detail read of Live Activity:
 * its work on one timeline, the collections that completed while it ran, the CPU time and memory it used, how it
 * compares with its route, and what it touched. Every child belongs to the request by its request id, never by thread
 * or time.
 *
 * @param available whether the journal still retains the request
 * @param unavailableReason why it does not, or {@code null}
 * @param requestId the request's id
 * @param route the route the request was grouped under, such as {@code GET /api/orders/{id}}
 * @param startedAt when the request started, in epoch milliseconds
 * @param durationMicros how long it took
 * @param status its response status
 * @param resources the CPU time, allocated bytes, and GC pauses its segments measured, or {@code null} when the
 *     {@code resources} source is off
 * @param timeline its recorded work, in start order, timed from the request's start
 * @param gcPauses the collections of pause collectors that completed while it ran, joined by id
 * @param routeComparison how it compares with its route's other requests, or {@code null} when it has no route
 * @param touched what it touched
 * @param notes what the profile could not show, such as events already evicted
 * @param orm its Hibernate sessions' work, or {@code null} when none was recorded (M4-9)
 */
public record RequestJournalProfileDto(
        boolean available,
        String unavailableReason,
        String requestId,
        String route,
        Long startedAt,
        Long durationMicros,
        Integer status,
        RequestResourcesDto resources,
        List<RequestTimelineItemDto> timeline,
        List<RequestGcPauseDto> gcPauses,
        RouteComparisonDto routeComparison,
        TouchedResourcesDto touched,
        List<String> notes,
        RequestOrmDto orm) {

    public RequestJournalProfileDto {
        timeline = DtoCollections.immutableCopy(timeline);
        gcPauses = DtoCollections.immutableCopy(gcPauses);
        notes = DtoCollections.immutableCopy(notes);
    }

    /** A request the journal does not retain. */
    public static RequestJournalProfileDto unavailable(String requestId, String reason) {
        return new RequestJournalProfileDto(
                false,
                reason,
                requestId,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                TouchedResourcesDto.NONE,
                List.of(),
                null);
    }
}
