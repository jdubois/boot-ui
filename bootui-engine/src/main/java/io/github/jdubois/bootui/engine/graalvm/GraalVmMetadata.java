package io.github.jdubois.bootui.engine.graalvm;

import java.util.List;

/**
 * Candidate entries for the generated GraalVM 25 {@code reachability-metadata.json} scaffold,
 * derived from the last scan. The booleans record dynamic shapes that static analysis cannot safely
 * infer so the generator can emit accurate completion guidance instead of invented registrations.
 */
public record GraalVmMetadata(
        List<String> reflectionTypes,
        List<String> serializationTypes,
        List<String> jniTypes,
        List<String> resourceGlobs,
        boolean proxyCallsDetected,
        boolean unsafeAllocationDetected,
        boolean foreignCallsDetected) {

    /** Standard externalized configuration resources that a native image must embed. */
    static final List<String> DEFAULT_RESOURCE_GLOBS = List.of(
            "application*.properties",
            "application*.yml",
            "application*.yaml",
            "logback-spring.xml",
            "log4j2-spring.xml");

    public GraalVmMetadata {
        reflectionTypes = List.copyOf(reflectionTypes);
        serializationTypes = List.copyOf(serializationTypes);
        jniTypes = List.copyOf(jniTypes);
        resourceGlobs = List.copyOf(resourceGlobs);
    }

    static GraalVmMetadata empty() {
        return new GraalVmMetadata(List.of(), List.of(), List.of(), DEFAULT_RESOURCE_GLOBS, false, false, false);
    }

    int reflectionEntryCount() {
        java.util.Set<String> entries = new java.util.HashSet<>(reflectionTypes);
        entries.addAll(serializationTypes);
        entries.addAll(jniTypes);
        return entries.size();
    }
}
