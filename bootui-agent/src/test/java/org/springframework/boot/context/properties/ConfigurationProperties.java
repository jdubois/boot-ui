package org.springframework.boot.context.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A stand-in for Spring Boot's annotation, by name: the code-paths sensor leaves classes carrying it alone. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigurationProperties {}
