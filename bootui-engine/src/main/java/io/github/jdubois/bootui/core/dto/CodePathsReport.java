package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Code Paths panel's summary ({@code docs/PLAN-v2.md} §5.14, M5-4b): this run's routes with a call tree of
 * application bean methods, ranked by their warm median, each with its top methods by self time, the sensor's status,
 * and the methods it excluded. Needs the BootUI agent's {@code code-paths} sensor; without it the report is unavailable
 * with the Java Agent panel's reason.
 *
 * @param available whether the code-paths sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param status the sensor's counters for this run, or {@code null} when unavailable
 * @param routes the routes with a tree, slowest warm median first
 * @param excludedMethods the methods adaptively excluded in this run, with why
 * @param limitations what the trees cannot see
 */
public record CodePathsReport(
        boolean available,
        String unavailableReason,
        CodePathsStatusDto status,
        List<CodePathsRouteDto> routes,
        List<CodePathsExcludedMethodDto> excludedMethods,
        List<String> limitations) {

    public CodePathsReport {
        routes = DtoCollections.immutableCopy(routes);
        excludedMethods = DtoCollections.immutableCopy(excludedMethods);
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** The report without the sensor. */
    public static CodePathsReport unavailable(String reason) {
        return new CodePathsReport(false, reason, null, List.of(), List.of(), List.of());
    }
}
