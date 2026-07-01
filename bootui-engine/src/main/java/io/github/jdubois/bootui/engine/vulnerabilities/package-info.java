/**
 * Framework-neutral dependency-vulnerability reporting surface: report assembly, dependency and
 * vulnerability ordering, and severity math over BootUI core dependency DTOs.
 *
 * <p>Plain Java (BootUI core DTOs + JDK only, no JSON or framework types). The neutral statics live in
 * {@link io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports}; adapters supply the
 * dependency inventory and the scan implementation by implementing the
 * {@link io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider} and
 * {@link io.github.jdubois.bootui.engine.vulnerabilities.VulnerabilityScanner} SPIs with their own
 * HTTP and JSON stack (OSV.dev), then route NOT_SCANNED/DISABLED reports through
 * {@code DependencyReports.report(...)} so ordering and severity counts stay shared.
 */
package io.github.jdubois.bootui.engine.vulnerabilities;
