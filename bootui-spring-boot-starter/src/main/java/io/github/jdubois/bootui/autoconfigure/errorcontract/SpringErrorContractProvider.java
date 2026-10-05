package io.github.jdubois.bootui.autoconfigure.errorcontract;

import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.ErrorContractProvider;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Spring {@link ErrorContractProvider} that reads the application's declared error contract from bean
 * <em>metadata</em> only.
 *
 * <p>It serves Spring MVC and Spring WebFlux from one implementation on purpose: {@code @ControllerAdvice},
 * {@code @RestControllerAdvice}, {@code @ExceptionHandler} and {@code @ResponseStatus} all live in
 * {@code spring-web}, and neither the servlet nor the reactive exception-resolution infrastructure is
 * touched here. Bean <em>types</em> are resolved without initializing {@code FactoryBean}s, no handler is
 * instantiated or invoked, and no exception is thrown to observe a response — the panel is a pure
 * declaration read.</p>
 *
 * <p>Discovery is memoized after the first read: the set of advice and controller beans is fixed once the
 * context has refreshed, and returning a stable list instance lets the engine's catalogue cache stay
 * O(1). BootUI's own components are excluded here, and the framework's own error controllers are excluded
 * by the engine, so the catalogue describes the host application's contract rather than its
 * container's.</p>
 */
public final class SpringErrorContractProvider implements ErrorContractProvider {

    /**
     * BootUI's own Spring-side packages, matching {@code BootUiSelfDataFilter}'s boundary so the panel
     * never reports BootUI's controllers as part of the host application's contract. The shared
     * {@code engine}/{@code spi} packages are deliberately not listed, and neither is the bare
     * {@code io.github.jdubois.bootui} prefix — a host application (BootUI's own sample apps included) may
     * legitimately live under it.
     *
     * <p>Framework-owned handlers such as Spring Boot's {@code BasicErrorController} are excluded by the
     * engine instead, so Spring and Quarkus cannot drift on that policy.</p>
     */
    private static final InternalPackageMatcher BOOTUI_PACKAGES = new InternalPackageMatcher(
            List.of("io.github.jdubois.bootui.autoconfigure", "io.github.jdubois.bootui.core"));

    /**
     * Reactive and asynchronous wrappers unwrapped before classifying the response body. Matched by name so
     * this class links neither Reactor nor any other optional type.
     */
    private static final Set<String> ASYNC_WRAPPERS = Set.of(
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux",
            "java.util.concurrent.CompletionStage",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.Callable",
            "org.springframework.web.context.request.async.DeferredResult",
            "org.springframework.web.context.request.async.WebAsyncTask");

    /** Return types whose status is built at runtime rather than declared. */
    /** Return types that carry the status the handler chose at runtime. */
    private static final Set<String> DYNAMIC_STATUS_TYPES = Set.of(
            "org.springframework.http.ResponseEntity",
            "org.springframework.web.servlet.function.ServerResponse",
            "org.springframework.web.reactive.function.server.ServerResponse");

    /**
     * Return types that wrap the response body. A plain {@code HttpEntity} carries headers and a body but no
     * status, so it is unwrapped without claiming the status is dynamic.
     */
    private static final Set<String> BODY_ENVELOPE_TYPES =
            Set.of("org.springframework.http.ResponseEntity", "org.springframework.http.HttpEntity");

    private final ListableBeanFactory beanFactory;

    private volatile List<ErrorHandlerDescriptor> cached;

    public SpringErrorContractProvider(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public boolean available() {
        return beanFactory != null;
    }

    @Override
    public List<ErrorHandlerDescriptor> handlers() {
        if (beanFactory == null) {
            return List.of();
        }
        List<ErrorHandlerDescriptor> snapshot = cached;
        if (snapshot == null) {
            snapshot = discover();
            cached = snapshot;
        }
        return snapshot;
    }

    private List<ErrorHandlerDescriptor> discover() {
        List<ErrorHandlerDescriptor> descriptors = new ArrayList<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        for (String beanName : beanDefinitionNames()) {
            Class<?> type = userType(beanName);
            if (type == null || !visited.add(type)) {
                continue;
            }
            if (annotated(type, ControllerAdvice.class)) {
                collect(type, descriptors, true);
            } else if (annotated(type, Controller.class)) {
                collect(type, descriptors, false);
            }
        }
        descriptors.sort(Comparator.comparing(ErrorHandlerDescriptor::componentClassName)
                .thenComparing(ErrorHandlerDescriptor::methodName));
        return List.copyOf(descriptors);
    }

    /**
     * The registered bean definition names.
     *
     * <p>Deliberately <em>not</em> {@code getBeanNamesForAnnotation}: that method is documented to consider
     * the objects a {@code FactoryBean} produces, which initializes those factories — application code
     * running because a read-only panel was opened. Walking definitions and resolving each type with
     * {@code getType(name, false)} keeps the read declaration-only; a bean whose type cannot be determined
     * without initializing its factory is simply not reported.</p>
     */
    private String[] beanDefinitionNames() {
        try {
            return beanFactory.getBeanDefinitionNames();
        } catch (RuntimeException | LinkageError ex) {
            return new String[0];
        }
    }

    /** Whether the type carries the stereotype directly or through a meta-annotation. */
    private static boolean annotated(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        try {
            return AnnotatedElementUtils.hasAnnotation(type, annotation);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** The bean's user class (CGLIB proxies unwrapped), or {@code null} when it must not be reported. */
    private Class<?> userType(String beanName) {
        try {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null) {
                return null;
            }
            Class<?> userType = ClassUtils.getUserClass(type);
            if (BOOTUI_PACKAGES.matchesName(userType.getName())) {
                return null;
            }
            return userType;
        } catch (RuntimeException | LinkageError ex) {
            return null; // a bean whose type cannot be resolved contributes nothing
        }
    }

    private void collect(Class<?> type, List<ErrorHandlerDescriptor> descriptors, boolean advice) {
        Map<Method, ExceptionHandler> methods;
        try {
            methods = MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<ExceptionHandler>)
                    method -> AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class));
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        if (methods.isEmpty()) {
            return;
        }
        String source =
                advice ? ErrorHandlerDescriptor.SPRING_CONTROLLER_ADVICE : ErrorHandlerDescriptor.SPRING_CONTROLLER;
        String scope = advice ? adviceScope(type) : ErrorHandlerDescriptor.SCOPE_CONTROLLER;
        String scopeTarget = advice ? adviceSelectors(type) : type.getName();
        Integer declaredOrder = order(type);
        boolean dynamicPrecedence = declaredOrder == null && Ordered.class.isAssignableFrom(type);
        for (Map.Entry<Method, ExceptionHandler> entry : methods.entrySet()) {
            try {
                descriptors.add(describe(
                        type,
                        entry.getKey(),
                        entry.getValue(),
                        source,
                        scope,
                        scopeTarget,
                        declaredOrder,
                        dynamicPrecedence));
            } catch (RuntimeException | LinkageError ex) {
                // Skip a handler method that cannot be introspected rather than failing the panel.
            }
        }
    }

    private static ErrorHandlerDescriptor describe(
            Class<?> type,
            Method method,
            ExceptionHandler annotation,
            String source,
            String scope,
            String scopeTarget,
            Integer declaredOrder,
            boolean dynamicPrecedence) {
        ResolvableType returnType = ResolvableType.forMethodReturnType(method, type);
        ResolvableType unwrapped = unwrapAsync(returnType);
        Class<?> rawReturn = unwrapped.resolve();
        boolean dynamicStatus = rawReturn != null && DYNAMIC_STATUS_TYPES.contains(rawReturn.getName());
        boolean envelope = rawReturn != null && BODY_ENVELOPE_TYPES.contains(rawReturn.getName());
        ResolvableType bodyType = envelope ? unwrapped.getGeneric(0) : unwrapped;
        // Outside an envelope, a handler that does not write the response body renders a view: its return
        // value is a view name or a model, never the payload a client parses. A void return proves there is
        // no body either way.
        boolean bodyKnown = envelope || returnsNothing(rawReturn) || rendersBody(type, method);
        return new ErrorHandlerDescriptor(
                source,
                type.getName(),
                method.getName(),
                exceptionTypes(method, annotation),
                scope,
                scopeTarget,
                declaredOrder,
                dynamicPrecedence,
                declaredStatus(type, method),
                dynamicStatus,
                rawReturn == null ? null : typeName(rawReturn),
                bodyKnown ? typeName(bodyType.resolve()) : null,
                produces(annotation));
    }

    /** Whether the declaration proves there is no body at all. */
    private static boolean returnsNothing(Class<?> rawReturn) {
        return rawReturn == void.class || rawReturn == Void.class;
    }

    /**
     * Whether the handler writes the response body itself. {@code @RestControllerAdvice} and
     * {@code @RestController} are meta-annotated with {@code @ResponseBody}, so one lookup covers both them
     * and an explicit {@code @ResponseBody} on the class or the method.
     */
    private static boolean rendersBody(Class<?> type, Method method) {
        try {
            return AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
                    || AnnotatedElementUtils.hasAnnotation(type, ResponseBody.class);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** The media types the handler declares it produces, as declared on {@code @ExceptionHandler}. */
    private static List<String> produces(ExceptionHandler annotation) {
        String[] produces = annotation == null ? null : annotation.produces();
        if (produces == null || produces.length == 0) {
            return List.of();
        }
        return Arrays.stream(produces)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    /** Unwraps a reactive or asynchronous return type so the declared body type is classified, not the wrapper. */
    private static ResolvableType unwrapAsync(ResolvableType returnType) {
        ResolvableType current = returnType;
        for (int depth = 0; depth < 3; depth++) {
            Class<?> raw = current.resolve();
            if (raw == null || !ASYNC_WRAPPERS.contains(raw.getName())) {
                return current;
            }
            ResolvableType generic = current.getGeneric(0);
            if (generic == ResolvableType.NONE) {
                return current;
            }
            current = generic;
        }
        return current;
    }

    /**
     * The exception types the method declares it handles: the {@code @ExceptionHandler} value when present,
     * otherwise Spring's fallback of the method's {@code Throwable}-typed parameters.
     */
    private static List<String> exceptionTypes(Method method, ExceptionHandler annotation) {
        Class<? extends Throwable>[] declared = annotation == null ? null : annotation.value();
        if (declared != null && declared.length > 0) {
            return Arrays.stream(declared).map(Class::getName).toList();
        }
        List<String> fromParameters = new ArrayList<>();
        for (Class<?> parameter : method.getParameterTypes()) {
            if (Throwable.class.isAssignableFrom(parameter)) {
                fromParameters.add(parameter.getName());
            }
        }
        return fromParameters;
    }

    /** The statically declared status from {@code @ResponseStatus} on the method or its declaring class. */
    private static String declaredStatus(Class<?> type, Method method) {
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
        if (status == null) {
            status = AnnotatedElementUtils.findMergedAnnotation(type, ResponseStatus.class);
        }
        return status == null ? null : String.valueOf(status.code().value());
    }

    /**
     * A {@code @ControllerAdvice} with no selector applies application-wide; one narrowed by
     * {@code basePackages}, {@code basePackageClasses}, {@code assignableTypes} or {@code annotations} is
     * reported as scoped, because BootUI cannot decide from declarations alone which controllers it covers.
     */
    private static String adviceScope(Class<?> type) {
        return adviceSelectors(type) == null
                ? ErrorHandlerDescriptor.SCOPE_GLOBAL
                : ErrorHandlerDescriptor.SCOPE_SCOPED;
    }

    /** A human-readable summary of the advice's narrowing selectors, or {@code null} when it has none. */
    private static String adviceSelectors(Class<?> type) {
        MergedAnnotation<ControllerAdvice> advice =
                MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY).get(ControllerAdvice.class);
        if (!advice.isPresent()) {
            return null;
        }
        List<String> selectors = new ArrayList<>();
        addSelector(selectors, "basePackages", advice.getStringArray("basePackages"));
        addSelector(selectors, "basePackageClasses", classNames(advice.getClassArray("basePackageClasses")));
        addSelector(selectors, "assignableTypes", classNames(advice.getClassArray("assignableTypes")));
        addSelector(selectors, "annotations", classNames(advice.getClassArray("annotations")));
        return selectors.isEmpty() ? null : String.join("; ", selectors);
    }

    private static void addSelector(List<String> selectors, String name, String[] values) {
        if (values != null && values.length > 0) {
            selectors.add(name + "=" + String.join(", ", values));
        }
    }

    private static String[] classNames(Class<?>[] classes) {
        if (classes == null) {
            return new String[0];
        }
        return Arrays.stream(classes).map(Class::getName).toArray(String[]::new);
    }

    private static Integer order(Class<?> type) {
        try {
            return OrderUtils.getOrder(type);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String typeName(Class<?> type) {
        if (type == null) {
            return null;
        }
        return type.isArray() ? type.getComponentType().getName() + "[]" : type.getName();
    }
}
