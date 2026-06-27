/**
 * Framework-neutral CRaC (Coordinated Restore at Checkpoint) readiness advisor: a bounded, on-demand
 * scanner that runs a fixed registry of curated readiness checks against the host application's own
 * classes plus a live runtime inventory of auto-configured resources (connection pools, cache managers).
 *
 * <p>Plain Java (ArchUnit + JDK + BootUI core DTOs only); adapters supply the application base packages
 * to analyse through a {@code Supplier<List<String>>} seam (typically a {@code BasePackageProvider} SPI
 * implementation) and the live runtime inventory through a
 * {@link io.github.jdubois.bootui.engine.crac.CracRuntimeInventory} {@code Supplier} seam (so the
 * framework-specific bean inspection stays in the adapter), then wire
 * {@link io.github.jdubois.bootui.engine.crac.CracReadinessScanner} via an {@code @Bean} factory /
 * {@code @Produces} method.
 */
package io.github.jdubois.bootui.engine.crac;
