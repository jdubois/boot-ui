package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.function.Function;

/**
 * Shared entry point the engine advisor scanners (Architecture, REST API, GraalVM, CRaC) use to import the
 * host application's classes for ArchUnit analysis, bounded by the caller to the application's own base
 * packages.
 *
 * <p>It centralises one concern: while the import runs, ArchUnit's {@link ArchConfiguration} is pointed at
 * {@link OpenableLocationClassResolver} so classpath resolution never trips over a resource URL it cannot
 * open (see that class for the {@code quarkus:} motivation). Configuring an explicit {@code classResolver}
 * overrides {@code resolveMissingDependenciesFromClassPath} (ArchUnit only consults that flag when no
 * resolver is set), so the flag is intentionally left untouched and resolution stays fully enabled.
 *
 * <p>The configuration is applied through {@link ArchConfiguration#withThreadLocalScope(Function)}, which
 * runs the import against a thread-local copy of the global configuration and discards it afterwards (even
 * if the import throws). That keeps the change invisible to anything else sharing the JVM — notably the
 * engine's own build-time ArchUnit boundary tests, which must always observe the default resolver — without
 * mutating global state, and lets concurrent scans run independently.
 */
public final class ArchUnitClassImports {

    private ArchUnitClassImports() {}

    /**
     * Imports the supplied base packages with the {@link OpenableLocationClassResolver} active for the
     * duration of the import.
     */
    public static JavaClasses importPackages(List<String> basePackages) {
        return importPackages(basePackages, packages -> new ClassFileImporter().importPackages(packages));
    }

    // Visible for testing: the seam lets a test observe the ArchConfiguration that is active during the
    // import (and run the real importer) without reaching into ArchUnit's global state itself.
    static JavaClasses importPackages(List<String> basePackages, Function<List<String>, JavaClasses> importer) {
        return ArchConfiguration.withThreadLocalScope(config -> {
            config.setClassResolver(OpenableLocationClassResolver.class);
            // Clear any inherited arguments: OpenableLocationClassResolver only has a no-arg constructor,
            // and ArchUnit would otherwise look for a List<String> constructor when arguments are present.
            config.setClassResolverArguments();
            return importer.apply(basePackages);
        });
    }
}
