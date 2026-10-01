package javax.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal test stub standing in for the legacy JSR-330 {@code javax.inject.Inject}, which is absent from this module's
 * test classpath, so the ARCH-SPRING-024 fixtures can carry it. Rules match it by name. Not the real annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Inject {}
