package io.github.jdubois.bootui.engine.graalvm;

import java.util.List;

/**
 * Candidate entries for the generated {@code reachability-metadata.json} scaffold, derived from the
 * last scan. Reflection and serialization candidates are concrete application types; resource globs
 * cover the standard externalized configuration files.
 */
public record GraalVmMetadata(
        List<String> reflectionTypes, List<String> serializationTypes, List<String> resourceGlobs) {

    /** Standard externalized configuration resources that a native image must embed. */
    static final List<String> DEFAULT_RESOURCE_GLOBS =
            List.of("application*.properties", "application*.yml", "application*.yaml");

    public GraalVmMetadata {
        reflectionTypes = List.copyOf(reflectionTypes);
        serializationTypes = List.copyOf(serializationTypes);
        resourceGlobs = List.copyOf(resourceGlobs);
    }

    static GraalVmMetadata empty() {
        return new GraalVmMetadata(List.of(), List.of(), DEFAULT_RESOURCE_GLOBS);
    }
}
