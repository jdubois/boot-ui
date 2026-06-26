/**
 * Framework-neutral services and advisor engines that implement BootUI's behavior independently of any
 * host framework.
 *
 * <p>Engine types are plain Java: no Spring, CDI or Quarkus annotations, and no host-framework or
 * transport API on their signatures. They depend only on BootUI core DTOs, the
 * {@code io.github.jdubois.bootui.spi} interfaces, neutral {@code jakarta.*} contracts and Micrometer.
 * Each adapter constructs and wires them explicitly (Spring through an {@code @Bean} factory, Quarkus
 * through {@code @Produces}), injecting already-resolved optional handles so the engine never
 * statically references an optional dependency. The boundary is enforced by
 * {@link io.github.jdubois.bootui.engine.EngineBoundaryArchitectureTests}.
 */
package io.github.jdubois.bootui.engine;
