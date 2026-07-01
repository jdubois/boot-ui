/**
 * Framework-neutral service provider interfaces (SPIs) that the BootUI engine depends on and each
 * host-framework adapter implements.
 *
 * <p>Spring Boot implements these in {@code bootui-spring-autoconfigure}; Quarkus implements them in
 * {@code bootui-quarkus}. To preserve that portability, types in this package and its sub-packages
 * must never reference a host-framework or transport API. Their method signatures may use only BootUI
 * core DTOs, neutral {@code jakarta.*} contracts that every framework shares (e.g.
 * {@code jakarta.persistence}, {@code jakarta.sql}) and Micrometer. The boundary is enforced by
 * {@code io.github.jdubois.bootui.spi.SpiBoundaryArchitectureTests}.
 */
package io.github.jdubois.bootui.spi;
