/**
 * Shared ArchUnit class-import plumbing for the engine's advisor scanners (Architecture, REST API, GraalVM,
 * CRaC). Centralises how the host application's classes are imported for analysis so every scanner resolves
 * referenced types from the classpath consistently and without flooding the console when a runtime
 * classloader exposes resources under a URL protocol ArchUnit cannot open (e.g. the Quarkus
 * {@code quarkus:} scheme).
 *
 * <p>Plain Java (ArchUnit only); contains no framework-specific types, so it stays framework-neutral and is
 * reused unchanged by both the Spring Boot and Quarkus adapters.
 */
package io.github.jdubois.bootui.engine.archunit;
