/**
 * Framework-neutral ArchUnit hygiene advisor: a bounded, on-demand scanner that runs a fixed registry
 * of curated, project-agnostic architecture rules against the host application's own classes.
 *
 * <p>Plain Java (ArchUnit + BootUI core DTOs only); adapters supply the application base packages to
 * analyse through a {@code Supplier<List<String>>} seam (typically a {@code BasePackageProvider} SPI
 * implementation) and wire {@link io.github.jdubois.bootui.engine.architecture.ArchitectureScanner} via
 * an {@code @Bean} factory / {@code @Produces} method.
 */
package io.github.jdubois.bootui.engine.architecture;
