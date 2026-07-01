/**
 * Framework-neutral GraalVM native-image readiness advisor: a bounded, on-demand scanner that runs a
 * fixed registry of curated readiness checks against the host application's own classes plus an optional
 * classpath dependency-metadata survey.
 *
 * <p>Plain Java (ArchUnit + JDK + BootUI core DTOs only); adapters supply the application base
 * packages to analyse through a {@code Supplier<List<String>>} seam (typically a {@code BasePackageProvider}
 * SPI implementation), pass static {@code bootui.graalvm.*} dependency-survey settings as a
 * {@link io.github.jdubois.bootui.engine.graalvm.GraalVmDependencySettings} value record, supply the
 * reachability-metadata repository lookups through the
 * {@link io.github.jdubois.bootui.engine.graalvm.ReachabilityMetadataRepository} seam (so the JSON/HTTP
 * library stays in the adapter), and wire
 * {@link io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner} via an {@code @Bean} factory /
 * {@code @Produces} method.
 */
package io.github.jdubois.bootui.engine.graalvm;
