package io.github.jdubois.bootui.engine.graalvm;

/**
 * Transport seam for Oracle's GraalVM reachability-metadata repository. The engine owns the survey
 * orchestration, gating, per-scan caching, the version-coverage matching policy, and all user-facing
 * note formatting; an adapter supplies the network + JSON implementation (Spring Boot parses with
 * Jackson 3, Quarkus with Jackson 2) so {@code bootui-engine} stays framework- and JSON-library-neutral.
 *
 * <p>Implementations must perform at most one bounded HTTP GET per call and must never throw: transport
 * or parse failures are reported as {@link ReachabilityMetadataIndex#unavailable(String)}, and a
 * repository with no entry for the library (e.g. HTTP 404) as an empty
 * {@link ReachabilityMetadataIndex#of(java.util.List) index}. Returning raw index rows (rather than a
 * coverage verdict) keeps the matching policy in the engine so every adapter shares it.
 */
public interface ReachabilityMetadataRepository {

    /** Fetches the raw reachability-metadata {@code index.json} rows for the given (complete) coordinates. */
    ReachabilityMetadataIndex fetch(Coordinates coordinates);
}
