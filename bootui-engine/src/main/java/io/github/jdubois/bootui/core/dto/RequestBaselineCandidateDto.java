package io.github.jdubois.bootui.core.dto;

/**
 * A retained, complete HTTP request on the same route that can serve as a baseline comparison candidate.
 *
 * @param requestId the candidate request's id
 * @param startedAt when the candidate request started, in epoch milliseconds
 * @param durationMicros how long the candidate request took, in microseconds
 * @param status the candidate request's HTTP response status
 */
public record RequestBaselineCandidateDto(String requestId, long startedAt, long durationMicros, int status) {}
