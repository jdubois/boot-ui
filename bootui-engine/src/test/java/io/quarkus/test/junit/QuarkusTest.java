package io.quarkus.test.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal test stub standing in for the absent Quarkus {@code quarkus-junit5} library's
 * {@code @QuarkusTest} annotation, so the ARCH-CODE-013 test-framework-allowlist fixture can compile
 * and exercise the "io.quarkus.test.. is a disallowed test-framework package" detection without pulling
 * the real (heavyweight) dependency into this framework-neutral module's test scope. Not the real
 * annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface QuarkusTest {}
