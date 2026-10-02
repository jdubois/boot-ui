package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.engine.insights.ProxyBoundaries;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Resolves an application frame's proxy boundaries on Spring ({@code docs/PLAN-v2.md} §5.12), with Spring's
 * merged-annotation lookup over the type hierarchy, so a {@code @Transactional} declared on an interface or on the class
 * counts. Annotations are read by name, so an application without {@code spring-tx} loads nothing it lacks. Each frame
 * is resolved once.
 */
public final class SpringProxyBoundaries implements ProxyBoundaries {

    static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    static final String CACHEABLE = "org.springframework.cache.annotation.Cacheable";
    static final String CACHE_CONFIG = "org.springframework.cache.annotation.CacheConfig";
    static final String ASYNC = "org.springframework.scheduling.annotation.Async";

    /** The bean {@code @EnableTransactionManagement(mode = ASPECTJ)} registers. */
    static final String TRANSACTION_ASPECT = "org.springframework.transaction.config.internalTransactionAspect";

    private static final Set<String> REQUIRING = Set.of("REQUIRED", "MANDATORY", "REQUIRES_NEW", "NESTED");

    private static final int MAX_CACHED = 10_000;

    private final ClassLoader classLoader;
    private final ApplicationContext context;
    private final Map<String, Object> resolved = new ConcurrentHashMap<>();

    public SpringProxyBoundaries(ApplicationContext context) {
        this.context = context;
        this.classLoader = context == null ? ClassUtils.getDefaultClassLoader() : context.getClassLoader();
    }

    @Override
    public String notApplicable() {
        if (context != null && context.containsBean(TRANSACTION_ASPECT)) {
            return "Transactions are woven with AspectJ, which applies @Transactional to a call from inside the bean.";
        }
        return null;
    }

    @Override
    public Boundary resolve(String frame) {
        if (frame == null) {
            return null;
        }
        Object answer = resolved.get(frame);
        if (answer == null) {
            Boundary boundary = compute(frame);
            answer = boundary == null ? Boolean.FALSE : boundary;
            if (resolved.size() >= MAX_CACHED) {
                resolved.clear();
            }
            resolved.put(frame, answer);
        }
        return answer instanceof Boundary boundary ? boundary : null;
    }

    private Boundary compute(String frame) {
        int open = frame.indexOf('(');
        String qualified = open < 0 ? frame : frame.substring(0, open);
        int dot = qualified.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String className = qualified.substring(0, dot);
        String methodName = qualified.substring(dot + 1);
        Class<?> type;
        try {
            type = ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
        List<Boundary> candidates = new ArrayList<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            if (method.getName().equals(methodName) && !method.isBridge() && !method.isSynthetic()) {
                candidates.add(boundaryOf(type, method));
            }
        }
        if (candidates.isEmpty()) {
            return Boundary.NONE;
        }
        Boundary first = candidates.get(0);
        return candidates.stream().allMatch(first::equals) ? first : null;
    }

    static Boundary boundaryOf(Class<?> type, Method method) {
        if (Modifier.isStatic(method.getModifiers())) {
            return Boundary.NONE;
        }
        MergedAnnotations onMethod = MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY);
        MergedAnnotations onType = MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY);
        MergedAnnotation<?> transactional = present(onMethod.get(TRANSACTIONAL), onType.get(TRANSACTIONAL));
        boolean requiresTransaction = transactional != null
                && REQUIRING.contains(
                        String.valueOf(transactional.getValue("propagation").orElse("REQUIRED")));
        Set<String> caches = new LinkedHashSet<>();
        MergedAnnotation<?> cacheable = present(onMethod.get(CACHEABLE), onType.get(CACHEABLE));
        if (cacheable != null) {
            caches.addAll(names(cacheable, "cacheNames"));
            if (caches.isEmpty()) {
                MergedAnnotation<?> config = onType.get(CACHE_CONFIG);
                if (config.isPresent()) {
                    caches.addAll(names(config, "cacheNames"));
                }
            }
        }
        boolean async = present(onMethod.get(ASYNC), onType.get(ASYNC)) != null;
        return new Boundary(requiresTransaction, caches, async);
    }

    private static MergedAnnotation<?> present(MergedAnnotation<?> onMethod, MergedAnnotation<?> onType) {
        if (onMethod.isPresent()) {
            return onMethod;
        }
        return onType.isPresent() ? onType : null;
    }

    private static List<String> names(MergedAnnotation<?> annotation, String attribute) {
        return annotation
                .getValue(attribute, String[].class)
                .map(Arrays::asList)
                .orElse(List.of());
    }
}
