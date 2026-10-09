package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Code Paths' <b>Beans at runtime</b> ({@code docs/PLAN-v2.md} §5.14, M5-4c): the calls between application beans the
 * BootUI agent's code paths observed in this run, beside the dependencies the beans declare, so a declared dependency
 * not called in this run stands out. Never "unused": a run only shows the paths its requests took.
 *
 * @param available whether the code-paths sensor records for this application
 * @param unavailableReason why not, or {@code null}
 * @param beansAvailable whether the application's beans and their dependencies could be read
 * @param edges the edges, observed ones first, most calls first, then the declared ones by name, at most
 *     {@link #MAX_EDGES}
 * @param observedEdges how many bean-to-bean calls were observed
 * @param declaredEdges how many dependencies the beans declare between application beans
 * @param notCalled how many declared dependencies between observable beans were not called in this run
 * @param omitted edges left out past {@link #MAX_EDGES}
 * @param limitations what the edges cannot show
 */
public record CodePathsBeansReport(
        boolean available,
        String unavailableReason,
        boolean beansAvailable,
        List<CodePathsBeanEdgeDto> edges,
        int observedEdges,
        int declaredEdges,
        int notCalled,
        int omitted,
        List<String> limitations) {

    /** The most edges one read returns. */
    public static final int MAX_EDGES = 2_000;

    public CodePathsBeansReport {
        edges = DtoCollections.immutableCopy(edges);
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** The report of an application whose code-paths sensor does not record, saying why. */
    public static CodePathsBeansReport unavailable(String reason) {
        return new CodePathsBeansReport(false, reason, false, List.of(), 0, 0, 0, 0, List.of());
    }
}
