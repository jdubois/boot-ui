package io.github.jdubois.bootui.quarkus.scheduled;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor binding the deployment processor adds to every {@code @Scheduled} method when the scheduler is present,
 * so {@link QuarkusScheduledExecutionInterceptor} gives each run its own BootUI execution context
 * ({@code docs/PLAN-v2.md} §5.1). Applications never use it directly.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface BootUiScheduledExecution {}
