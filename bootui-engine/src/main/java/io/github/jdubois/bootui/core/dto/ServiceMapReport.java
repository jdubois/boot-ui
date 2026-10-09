package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Live Flow service map: the running application, the generic local HTTP client lane that reaches it,
 * and the outbound dependencies BootUI can derive from evidence it already retains.
 *
 * <p>The map is assembled entirely from bounded, already-masked buffers that other panels populate. Reading
 * it performs no network call, probe, DNS lookup, connection attempt, scan, or new interception, and it adds
 * no instrumentation. A relationship that cannot be derived safely is absent rather than guessed.</p>
 *
 * <p>Every source honors its own panel's enable/availability state: evidence from a disabled or unavailable
 * panel never reaches this report, and its absence is reported through {@link #sources()} and
 * {@link #warnings()} rather than silently.</p>
 *
 * @param available whether at least one map source contributed
 * @param unavailableReason why the map is empty when {@link #available()} is {@code false}, else {@code null}
 * @param generatedAt epoch milliseconds when this report was assembled
 * @param application the centered running-application node, or {@code null} when unavailable
 * @param nodes the inbound lane and outbound dependency nodes, excluding {@link #application()}
 * @param edges the relationships anchored on the application
 * @param truncation how much was withheld to keep the graph bounded
 * @param sources labels of the evidence sources that contributed
 * @param warnings non-fatal notes, such as evidence that had to be summarized or could not be attributed
 */
public record ServiceMapReport(
        boolean available,
        String unavailableReason,
        long generatedAt,
        ServiceMapNodeDto application,
        List<ServiceMapNodeDto> nodes,
        List<ServiceMapEdgeDto> edges,
        ServiceMapTruncationDto truncation,
        List<String> sources,
        List<String> warnings) {

    public ServiceMapReport {
        nodes = DtoCollections.immutableCopy(nodes);
        edges = DtoCollections.immutableCopy(edges);
        sources = DtoCollections.immutableCopy(sources);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
