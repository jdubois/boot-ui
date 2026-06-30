package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Supplies the host application's own base packages — the package roots that BootUI's ArchUnit-based
 * advisors (Architecture, GraalVM readiness, CRaC readiness, REST API, ...) bound their bytecode import
 * to, so analysis covers the application's own code rather than its dependencies.
 *
 * <p>This is the framework-neutral seam shared by every advisor scanner: the engine always imports and
 * analyses the classes under these packages, but <em>how</em> the application base packages are
 * discovered is a per-framework detail. The Spring Boot adapter resolves them from
 * {@code AutoConfigurationPackages} (the {@code @SpringBootApplication} package(s)); the Quarkus adapter
 * resolves them from its build-time Jandex index / application package. The packages are read
 * <em>live</em> on every scan (not snapshotted at construction), and implementations must fail soft —
 * returning an empty list — when the base packages cannot be determined, so the advisor degrades to a
 * stable "nothing to analyse" report instead of failing.
 */
@FunctionalInterface
public interface BasePackageProvider {

    /** The host application base packages to analyse; an empty list when none can be determined. */
    List<String> basePackages();
}
