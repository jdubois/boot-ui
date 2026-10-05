package io.github.jdubois.bootui.autoconfigure.reactive;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * BootUI-only WebFlux adapter boundary that keeps API handlers off Reactor Netty event-loop threads.
 *
 * <p>Most controllers shared with the servlet adapter intentionally return plain DTOs or
 * {@code ResponseEntity} values. Their handler bodies can perform blocking work such as advisor scans,
 * classpath inspection, JVM diagnostics, filesystem access, downloads, and bounded network calls. This
 * adapter delegates to WebFlux's fully configured {@link RequestMappingHandlerAdapter}, preserving its
 * argument resolution, validation, response, and exception contracts. Its dedicated delegate uses
 * WebFlux's native blocking-method scheduler on {@link Schedulers#boundedElastic()}, so handler
 * invocation stays off the event loop even when asynchronous request-body decoding completes there.</p>
 *
 * <p>Selection follows BootUI's centralized controller convention: API controllers declare a
 * class-level mapping rooted at {@code ${bootui.api-path:...}}. This avoids a per-controller or
 * per-endpoint allowlist, so newly shared handlers inherit the correct execution model automatically.
 * The adapter declines host-application and shell handlers, leaving the application's global WebFlux
 * blocking-execution policy untouched.</p>
 */
public final class ReactiveBootUiHandlerAdapter implements HandlerAdapter, Ordered, SmartInitializingSingleton {

    private static final String API_PATH_PLACEHOLDER_PREFIX = "${bootui.api-path:";

    private final ObjectProvider<RequestMappingHandlerAdapter> delegateProvider;
    private final ApplicationContext applicationContext;
    private final Executor executor;
    private volatile RequestMappingHandlerAdapter bootUiDelegate;

    public ReactiveBootUiHandlerAdapter(
            ObjectProvider<RequestMappingHandlerAdapter> delegateProvider, ApplicationContext applicationContext) {
        this(
                delegateProvider,
                applicationContext,
                command -> Schedulers.boundedElastic().schedule(command));
    }

    ReactiveBootUiHandlerAdapter(
            ObjectProvider<RequestMappingHandlerAdapter> delegateProvider,
            ApplicationContext applicationContext,
            Executor executor) {
        this.delegateProvider = delegateProvider;
        this.applicationContext = applicationContext;
        this.executor = executor;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean supports(Object handler) {
        return handler instanceof HandlerMethod handlerMethod && isBootUiApi(handlerMethod);
    }

    @Override
    public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
        return bootUiDelegate().handle(exchange, handler);
    }

    @Override
    public void afterSingletonsInstantiated() {
        RequestMappingHandlerAdapter delegate = delegateProvider.getIfAvailable();
        if (delegate != null) {
            bootUiDelegate = createBootUiDelegate(delegate);
        }
    }

    private RequestMappingHandlerAdapter bootUiDelegate() {
        RequestMappingHandlerAdapter delegate = bootUiDelegate;
        if (delegate == null) {
            synchronized (this) {
                delegate = bootUiDelegate;
                if (delegate == null) {
                    delegate = createBootUiDelegate(delegateProvider.getObject());
                    bootUiDelegate = delegate;
                }
            }
        }
        return delegate;
    }

    private RequestMappingHandlerAdapter createBootUiDelegate(RequestMappingHandlerAdapter source) {
        RequestMappingHandlerAdapter delegate = new RequestMappingHandlerAdapter();
        delegate.setMessageReaders(source.getMessageReaders());
        delegate.setWebBindingInitializer(source.getWebBindingInitializer());
        delegate.setArgumentResolverConfigurer(source.getArgumentResolverConfigurer());
        delegate.setContentTypeResolver(source.getContentTypeResolver());
        delegate.setReactiveAdapterRegistry(source.getReactiveAdapterRegistry());
        delegate.setBlockingExecutor(executor);
        delegate.setBlockingMethodPredicate(handlerMethod -> true);
        delegate.setApplicationContext(applicationContext);
        try {
            delegate.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize BootUI's reactive handler adapter", ex);
        }
        return delegate;
    }

    static boolean isBootUiApi(HandlerMethod handlerMethod) {
        return isBootUiApi(handlerMethod.getBeanType());
    }

    static boolean isBootUiApi(Class<?> controllerType) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controllerType, RequestMapping.class);
        if (mapping == null) {
            return false;
        }
        return Stream.concat(Arrays.stream(mapping.path()), Arrays.stream(mapping.value()))
                .anyMatch(path -> path.startsWith(API_PATH_PLACEHOLDER_PREFIX));
    }
}
