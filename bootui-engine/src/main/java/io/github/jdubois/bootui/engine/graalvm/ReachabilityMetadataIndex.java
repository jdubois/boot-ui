package io.github.jdubois.bootui.engine.graalvm;

import java.util.List;

/**
 * Raw rows parsed from a dependency's reachability-metadata {@code index.json}, or a reason the lookup
 * could not be completed. This is intentionally a transport DTO carrying <em>no</em> coverage verdict:
 * the engine derives {@code covered} / {@code repositoryEntryExists} and the user-facing notes from
 * these rows, so the Spring Boot and Quarkus adapters share one matching policy instead of each
 * re-implementing it. Supplied by a {@link ReachabilityMetadataRepository} adapter implementation.
 */
public record ReachabilityMetadataIndex(List<Entry> entries, String lookupError) {

    public ReachabilityMetadataIndex {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * A completed lookup carrying the given index rows. An empty list means the repository has no entry
     * for the library (the engine reports it as "no entry", not as an error).
     */
    public static ReachabilityMetadataIndex of(List<Entry> entries) {
        return new ReachabilityMetadataIndex(entries, null);
    }

    /** A lookup that could not be completed; {@code reason} is surfaced (sanitized) to the panel. */
    public static ReachabilityMetadataIndex unavailable(String reason) {
        return new ReachabilityMetadataIndex(List.of(), reason);
    }

    /**
     * One {@code index.json} row: a published {@code metadata-version}, the library versions it was
     * tested against, and whether it is flagged as the {@code latest} metadata for the library.
     */
    public record Entry(String metadataVersion, List<String> testedVersions, boolean latest) {

        public Entry {
            testedVersions = testedVersions == null ? List.of() : List.copyOf(testedVersions);
        }
    }
}
