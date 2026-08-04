/**
 * Framework-neutral WebFlux-native (reactive) Spring Security advisor: a bounded, on-demand scanner
 * that runs a curated registry of {@code SEC-RXF-*} best-practice checks against a passive
 * observation of the host application's reactive security configuration.
 *
 * <p>Plain Java ({@code java.util.*} + BootUI core DTOs only, no Reactor, no Spring, no JSON); the
 * Spring adapter collects the framework observation (registered {@code SecurityWebFilterChain} beans
 * — excluding BootUI's own permit-all chain — CORS sources, OAuth2/JWT beans, and a precomputed
 * environment snapshot) through a {@link io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityObservation}
 * {@code Supplier} seam, then wires {@link io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityScanner}
 * via an {@code @Bean} factory method. This advisor is distinct in scope from the raw Spring Security
 * panel (which renders the filter chains/endpoints as-is): this one evaluates them against a
 * best-practice ruleset and reports violations.
 */
package io.github.jdubois.bootui.engine.reactivesecurity;
