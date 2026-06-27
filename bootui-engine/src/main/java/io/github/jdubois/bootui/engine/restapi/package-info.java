/**
 * Framework-neutral REST API Advisor: a bounded, on-demand scanner that derives a read-only handler
 * model from the host application's own controllers and runs a fixed registry of curated, project-
 * agnostic REST best-practice rules against it.
 *
 * <p>Plain Java (ArchUnit + BootUI core DTOs only); adapters supply the application base packages to
 * analyse through a {@code Supplier<List<String>>} seam (typically a {@code BasePackageProvider} SPI
 * implementation), probe springdoc/OpenAPI presence through a {@code BooleanSupplier}, and wire
 * {@link io.github.jdubois.bootui.engine.restapi.RestApiScanner} via an {@code @Bean} factory /
 * {@code @Produces} method.
 */
package io.github.jdubois.bootui.engine.restapi;
