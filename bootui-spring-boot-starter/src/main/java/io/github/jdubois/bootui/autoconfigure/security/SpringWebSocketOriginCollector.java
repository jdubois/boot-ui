package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.security.WebSocketOriginObservation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.StandardMethodMetadata;

/**
 * Origin-only reader of already-held Spring registries. No getBeansOfType/getType/getBean, topology
 * report, handler, interceptor, FactoryBean or overridable metadata getter is called. Optional web
 * stack types are referenced by name only. Unknown implementations remain a coverage limit.
 */
public final class SpringWebSocketOriginCollector {
    private static final String WS = "org.springframework.web.socket.";
    private static final String MVC = "org.springframework.web.servlet.";
    private static final String FLUX = "org.springframework.web.reactive.";
    private static final String CORS = "org.springframework.web.cors.CorsConfiguration";
    private static final int MAX_REGISTRATIONS = 4096;

    static final List<String> FIELD_TYPES = List.of(
            MVC + "DispatcherServlet",
            FLUX + "DispatcherHandler",
            MVC + "handler.AbstractUrlHandlerMapping",
            FLUX + "handler.AbstractUrlHandlerMapping",
            WS + "server.support.WebSocketHttpRequestHandler",
            WS + "server.support.OriginHandshakeInterceptor",
            WS + "sockjs.support.SockJsHttpRequestHandler",
            WS + "sockjs.support.AbstractSockJsService",
            CORS,
            CORS + "$OriginPattern",
            "org.springframework.web.util.pattern.PathPattern",
            "java.util.Collections$UnmodifiableList");

    private final ApplicationContext applicationContext;
    private final BootUiProperties properties;

    public SpringWebSocketOriginCollector(ApplicationContext applicationContext, BootUiProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    public WebSocketOriginObservation collect() {
        if (!properties.isPanelEnabled("websockets")) {
            return WebSocketOriginObservation.unavailable();
        }
        var builder = new WebSocketOriginObservation.Builder();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ApplicationContext current = applicationContext;
        int contexts = 0;
        int[] remaining = {MAX_REGISTRATIONS};
        while (current != null && contexts++ < 8) {
            if (!(current instanceof ConfigurableApplicationContext context) || !context.isActive()) {
                builder.incomplete();
                break;
            }
            var factory = context.getBeanFactory();
            if (factory.getBeanDefinitionCount() > MAX_REGISTRATIONS) {
                builder.incomplete();
            } else {
                for (String name : factory.getBeanDefinitionNames()) {
                    if (!factory.containsSingleton(name) && mappingDefinition(factory.getBeanDefinition(name))) {
                        builder.incomplete();
                    }
                }
            }
            for (String name : factory.getSingletonNames()) {
                if (!consume(remaining, builder)) {
                    return builder.build();
                }
                // Registered names can include early references whose lookup invokes an ObjectFactory.
                if (!factory.containsSingleton(name)) {
                    builder.incomplete();
                    continue;
                }
                try {
                    inspect(factory.getSingleton(name), seen, remaining, builder);
                } catch (UnsupportedMetadata ex) {
                    builder.incomplete();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    builder.failed();
                }
            }
            current = context.getParent();
        }
        if (current != null) {
            builder.incomplete();
        }
        return builder.build();
    }

    private static boolean mappingDefinition(BeanDefinition definition) {
        Class<?> type = definition.getClass();
        if (!(definition instanceof AbstractBeanDefinition nativeDefinition)
                || type.getClassLoader() != AbstractBeanDefinition.class.getClassLoader()
                || !Set.of(
                                "org.springframework.beans.factory.support.RootBeanDefinition",
                                "org.springframework.beans.factory.support.GenericBeanDefinition",
                                "org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition",
                                "org.springframework.context.annotation.ScannedGenericBeanDefinition",
                                "org.springframework.context.support.GenericApplicationContext$ClassDerivedBeanDefinition",
                                "org.springframework.context.annotation.ConfigurationClassBeanDefinitionReader$ConfigurationClassBeanDefinition")
                        .contains(type.getName())
                || nativeDefinition.isAbstract()) {
            return false;
        }
        if (nativeDefinition.hasBeanClass() && mappingType(nativeDefinition.getBeanClass())) {
            return true;
        }
        String className = nativeDefinition.getBeanClassName();
        if (Set.of(WS + "server.support.WebSocketHandlerMapping", FLUX + "handler.SimpleUrlHandlerMapping")
                .contains(className == null ? "" : className)) {
            return true;
        }
        return definition instanceof AnnotatedBeanDefinition annotated
                && annotated.getFactoryMethodMetadata() instanceof StandardMethodMetadata metadata
                && metadata.getClass() == StandardMethodMetadata.class
                && mappingType(metadata.getIntrospectedMethod().getReturnType());
    }

    private static boolean mappingType(Class<?> type) {
        return isType(type, MVC + "HandlerMapping") || isType(type, FLUX + "HandlerMapping");
    }

    private static void inspect(
            Object object, Set<Object> seen, int[] remaining, WebSocketOriginObservation.Builder builder)
            throws ReflectiveOperationException {
        if (object == null || !seen.add(object)) {
            return;
        }
        if (isType(object, MVC + "DispatcherServlet") || isType(object, FLUX + "DispatcherHandler")) {
            String owner =
                    isType(object, MVC + "DispatcherServlet") ? MVC + "DispatcherServlet" : FLUX + "DispatcherHandler";
            Object mappings = field(object, owner, "handlerMappings");
            if (mappings == null) {
                builder.incomplete();
                return;
            }
            for (Object mapping : nativeList(mappings)) {
                if (!consume(remaining, builder)) {
                    break;
                }
                inspect(mapping, seen, remaining, builder);
            }
        } else if (isType(object, WS + "server.support.WebSocketHandlerMapping")) {
            if (!exact(object, WS + "server.support.WebSocketHandlerMapping")) {
                builder.incomplete();
            }
            mappings(field(object, MVC + "handler.AbstractUrlHandlerMapping", "handlerMap"), false, remaining, builder);
            for (String special : List.of("rootHandler", "wildcardHandler")) {
                Object handler = field(object, MVC + "handler.AbstractUrlHandlerMapping", special);
                if (handler != null && consume(remaining, builder)) {
                    endpoint("rootHandler".equals(special) ? "/" : "/*", handler, builder);
                }
            }
        } else if (isType(object, FLUX + "handler.SimpleUrlHandlerMapping")) {
            if (!exact(object, FLUX + "handler.SimpleUrlHandlerMapping")) {
                builder.incomplete();
            }
            mappings(field(object, FLUX + "handler.AbstractUrlHandlerMapping", "handlerMap"), true, remaining, builder);
        }
    }

    private static boolean consume(int[] remaining, WebSocketOriginObservation.Builder builder) {
        if (remaining[0]-- <= 0) {
            builder.incomplete();
            return false;
        }
        return true;
    }

    private static void mappings(
            Object value, boolean reactive, int[] remaining, WebSocketOriginObservation.Builder builder)
            throws ReflectiveOperationException {
        if (!(value instanceof Map<?, ?> map)
                || !(value.getClass() == java.util.LinkedHashMap.class
                        || value.getClass() == java.util.HashMap.class)) {
            builder.incomplete();
            return;
        }
        for (var entry : map.entrySet()) {
            if (builder.atCapacity()) {
                builder.incomplete();
                break;
            }
            if (!consume(remaining, builder)) {
                break;
            }
            try {
                Object handler = entry.getValue();
                String path = entry.getKey() instanceof String text
                        ? text
                        : (String) field(
                                entry.getKey(), "org.springframework.web.util.pattern.PathPattern", "patternString");
                if (reactive) {
                    if (isType(handler, FLUX + "socket.WebSocketHandler")) {
                        builder.endpoint(path, null, false);
                    } else if (handler instanceof String) {
                        builder.incomplete();
                    }
                } else {
                    endpoint(path, handler, builder);
                }
            } catch (UnsupportedMetadata ex) {
                builder.incomplete();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                // Retain readable siblings, but never turn an unreadable registration into a pass.
                builder.failed();
            }
        }
    }

    private static void endpoint(String path, Object handler, WebSocketOriginObservation.Builder builder)
            throws ReflectiveOperationException {
        List<String> origins = new ArrayList<>();
        boolean known = false;
        boolean readable = true;
        if (exact(handler, WS + "server.support.WebSocketHttpRequestHandler")) {
            int count = 0;
            for (Object interceptor :
                    nativeList(field(handler, WS + "server.support.WebSocketHttpRequestHandler", "interceptors"))) {
                if (++count > WebSocketOriginObservation.MAX_ORIGINS) {
                    builder.incomplete();
                    readable = false;
                    break;
                }
                if (exact(interceptor, WS + "server.support.OriginHandshakeInterceptor")) {
                    known = true;
                    try {
                        readable &= readOrigins(
                                field(
                                        interceptor,
                                        WS + "server.support.OriginHandshakeInterceptor",
                                        "corsConfiguration"),
                                origins,
                                builder);
                    } catch (UnsupportedMetadata ex) {
                        builder.incomplete();
                        readable = false;
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                        builder.failed();
                        readable = false;
                    }
                }
            }
        } else if (exact(handler, WS + "sockjs.support.SockJsHttpRequestHandler")) {
            Object service = field(handler, WS + "sockjs.support.SockJsHttpRequestHandler", "sockJsService");
            if (exact(service, WS + "sockjs.transport.handler.DefaultSockJsService")
                    || exact(service, WS + "sockjs.transport.TransportHandlingSockJsService")) {
                known = true;
                try {
                    readable = readOrigins(
                            field(service, WS + "sockjs.support.AbstractSockJsService", "corsConfiguration"),
                            origins,
                            builder);
                } catch (UnsupportedMetadata ex) {
                    builder.incomplete();
                    readable = false;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    builder.failed();
                    readable = false;
                }
            }
        }
        builder.endpoint(path, origins, known && readable);
    }

    private static boolean readOrigins(Object config, List<String> origins, WebSocketOriginObservation.Builder builder)
            throws ReflectiveOperationException {
        if (!exact(config, CORS)) {
            throw new UnsupportedMetadata();
        }
        for (String fieldName : List.of("allowedOrigins", "allowedOriginPatterns")) {
            Object values = field(config, CORS, fieldName);
            if (values == null) {
                continue;
            }
            for (Object value : nativeList(values)) {
                if (origins.size() >= WebSocketOriginObservation.MAX_ORIGINS) {
                    builder.incomplete();
                    return false;
                }
                origins.add(
                        value instanceof String text
                                ? text
                                : (String) field(value, CORS + "$OriginPattern", "declaredPattern"));
            }
        }
        return true;
    }

    private static List<?> nativeList(Object value) {
        // Spring's dispatcher owns an unmodifiable list; inspect its backing list before iterating.
        // A JDK wrapper alone is not proof that its delegate is callback-free.
        for (int depth = 0;
                value != null
                        && depth < 8
                        && (value.getClass().getName().equals("java.util.Collections$UnmodifiableRandomAccessList")
                                || value.getClass().getName().equals("java.util.Collections$UnmodifiableList"));
                depth++) {
            Class<?> owner = value.getClass().getName().equals("java.util.Collections$UnmodifiableList")
                    ? value.getClass()
                    : value.getClass().getSuperclass();
            value = PassiveJdkField.read(value, owner, "list", List.class);
            if (value == null) {
                throw new UnsupportedMetadata();
            }
        }
        if (value instanceof List<?> list
                && Set.of(
                                "java.util.ArrayList",
                                "java.util.Arrays$ArrayList",
                                "java.util.Collections$EmptyList",
                                "java.util.Collections$SingletonList",
                                "java.util.ImmutableCollections$List12",
                                "java.util.ImmutableCollections$ListN")
                        .contains(value.getClass().getName())) {
            return list;
        }
        if (value == null) {
            throw new IllegalStateException("Missing native metadata container");
        }
        throw new UnsupportedMetadata();
    }

    private static final class UnsupportedMetadata extends RuntimeException {}

    private static Object field(Object object, String owner, String name) throws ReflectiveOperationException {
        if (object == null) {
            throw new IllegalStateException("Missing native metadata");
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            if (owner.equals(type.getName())) {
                Field field = type.getDeclaredField(name);
                if (!field.trySetAccessible()) {
                    throw new IllegalAccessException(name);
                }
                return field.get(object);
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean exact(Object object, String name) {
        return object != null && name.equals(object.getClass().getName());
    }

    private static boolean isType(Object object, String name) {
        if (object == null) {
            return false;
        }
        return isType(object.getClass(), name);
    }

    private static boolean isType(Class<?> type, String name) {
        if (type == null) {
            return false;
        }
        if (name.equals(type.getName())) {
            return true;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (isType(iface, name)) {
                return true;
            }
        }
        return isType(type.getSuperclass(), name);
    }
}
