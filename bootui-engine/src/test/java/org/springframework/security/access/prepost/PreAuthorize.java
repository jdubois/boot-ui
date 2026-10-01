package org.springframework.security.access.prepost;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal test stub standing in for Spring Security's {@code @PreAuthorize}, which is absent from this module's test
 * classpath, so the proxy rule fixtures can carry it. Rules match it by name. Not the real annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PreAuthorize {
    String value();
}
