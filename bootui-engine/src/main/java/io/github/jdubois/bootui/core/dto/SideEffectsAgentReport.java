package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Side Effects for agents ({@code get_side_effects}, {@code bootui side-effects}): every sensor's coverage, then the
 * rows matching {@code query} across the sensors this version ships, most frequent first, at most {@code limit}.
 *
 * @param available whether the BootUI agent records side effects this run
 * @param unavailableReason why not, or {@code null}
 * @param query the query as given: empty for every row, else a sensor id, or part of a row's attribution, target, or
 *     call site
 * @param sensors every Side Effects sensor with its coverage
 * @param matched how many rows matched
 * @param rows the matching rows, at most {@code limit}
 * @param omitted how many matching rows were left out past the limit
 * @param limitations what the rows cannot see
 * @param requestValueMatching whether request values are matched against sinks this run (the setting is on and the
 *     {@code security-sinks} sensor's holder is active), or {@code null} when side effects are unavailable
 * @param unconfirmedSinkMatches the security-sinks matches not shown yet because a second request has not confirmed
 *     them; a count, never a value
 */
public record SideEffectsAgentReport(
        boolean available,
        String unavailableReason,
        String query,
        List<SideEffectsSensorDto> sensors,
        int matched,
        List<SideEffectsRowDto> rows,
        int omitted,
        List<String> limitations,
        Boolean requestValueMatching,
        long unconfirmedSinkMatches) {

    /** The rows an agent gets when it asks for no limit. */
    public static final int DEFAULT_LIMIT = 20;

    public SideEffectsAgentReport {
        sensors = DtoCollections.immutableCopy(sensors);
        rows = DtoCollections.immutableCopy(rows);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
