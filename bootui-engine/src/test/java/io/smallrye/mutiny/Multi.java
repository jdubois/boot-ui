package io.smallrye.mutiny;

/**
 * Minimal test stub standing in for the absent SmallRye Mutiny library (Quarkus' reactive type,
 * registered with Spring's {@code ReactiveAdapterRegistry} when on the classpath), so
 * ARCH-SPRING-012 fixtures can compile and exercise the "Multi is a Spring-recognized deferred
 * return type" detection. Not the real library.
 */
public class Multi<T> {}
