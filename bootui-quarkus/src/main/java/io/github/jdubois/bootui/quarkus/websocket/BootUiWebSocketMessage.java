package io.github.jdubois.bootui.quarkus.websocket;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor binding the deployment processor adds to every {@code @OnTextMessage} and {@code @OnBinaryMessage}
 * method of an application's {@code @WebSocket} endpoints, so {@link QuarkusWebSocketMessageInterceptor} makes each
 * message an execution ({@code docs/PLAN-v2.md} §5.18, M4-10). Applications never use it directly.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface BootUiWebSocketMessage {}
