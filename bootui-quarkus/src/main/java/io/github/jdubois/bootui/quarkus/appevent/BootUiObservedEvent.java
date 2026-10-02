package io.github.jdubois.bootui.quarkus.appevent;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor binding the deployment processor adds to every application observer method, so {@link
 * QuarkusObserverInterceptor} records its runs in the runtime journal ({@code docs/PLAN-v2.md} §5.18, M4-8).
 * Applications never use it directly.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface BootUiObservedEvent {}
