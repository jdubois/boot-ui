package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

/**
 * Reads the WebFlux WebSocket topology from the {@link SimpleUrlHandlerMapping} beans applications use to
 * publish reactive {@link WebSocketHandler}s.
 *
 * <p>WebFlux has no STOMP broker — {@code @EnableWebSocketMessageBroker} is servlet-only — and no
 * sanctioned seam for observing frames or enumerating live sessions without wrapping application handler
 * beans. BootUI therefore reports the endpoints honestly and marks frame capture unsupported with a
 * reason, rather than silently showing an always-empty activity list or reaching into private state.</p>
 *
 * <p>Manifest availability reads existing registrations without resolving beans or calling handlers.
 * The separate topology report reads handler metadata, including advertised subprotocols, without
 * opening a session or sending a frame.</p>
 */
public class ReactiveWebSocketMetadataProvider implements WebSocketMetadataProvider {

    public static final String FRAMEWORK = "spring-webflux-websocket";

    static final String CAPTURE_UNAVAILABLE =
            "Spring WebFlux has no supported interception point for WebSocket frames or sessions, so BootUI "
                    + "lists endpoints only. Frame activity is available on the Spring MVC stack, where STOMP "
                    + "exposes a decorator seam.";

    private final ApplicationContext applicationContext;

    public ReactiveWebSocketMetadataProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public boolean hasEndpoints() {
        return initializedMappings().map(RegisteredMapping::mapping).anyMatch(mapping -> {
            var handlers = mapping.getHandlerMap();
            return handlers != null && handlers.values().stream().anyMatch(WebSocketHandler.class::isInstance);
        });
    }

    private Stream<RegisteredMapping> initializedMappings() {
        if (!(applicationContext instanceof ConfigurableApplicationContext context) || !context.isActive()) {
            return Stream.empty();
        }
        var factory = context.getBeanFactory();
        String[] singletonNames = factory.getSingletonNames();
        // Keep local definition order for paths exposed by more than one mapping.
        var localMappings = Stream.concat(
                        Arrays.stream(factory.getBeanDefinitionNames()),
                        Arrays.stream(singletonNames).filter(name -> !factory.containsBeanDefinition(name)))
                .filter(factory::containsSingleton)
                .map(factory::getSingleton)
                .filter(SimpleUrlHandlerMapping.class::isInstance)
                .map(SimpleUrlHandlerMapping.class::cast)
                .map(mapping -> new RegisteredMapping(mapping, true));
        // The dispatcher also holds FactoryBean products, prototype mappings and ancestor mappings.
        var dispatchedMappings = Arrays.stream(singletonNames)
                .filter(factory::containsSingleton)
                .map(factory::getSingleton)
                .filter(DispatcherHandler.class::isInstance)
                .map(DispatcherHandler.class::cast)
                .map(DispatcherHandler::getHandlerMappings)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(SimpleUrlHandlerMapping.class::isInstance)
                .map(SimpleUrlHandlerMapping.class::cast)
                .map(mapping -> new RegisteredMapping(mapping, false));
        return Stream.concat(localMappings, dispatchedMappings);
    }

    private record RegisteredMapping(SimpleUrlHandlerMapping mapping, boolean hasLocalOrder) {}

    private List<SimpleUrlHandlerMapping> orderedReportMappings() {
        List<SimpleUrlHandlerMapping> mappings = new ArrayList<>();
        Set<SimpleUrlHandlerMapping> remaining = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> paths = new HashSet<>();
        boolean needsLocalOrder = false;
        for (var registration : initializedMappings().toList()) {
            var mapping = registration.mapping();
            if (!remaining.add(mapping)) {
                continue;
            }
            mappings.add(mapping);
            var handlers = mapping.getHandlerMap();
            if (handlers == null || needsLocalOrder) {
                continue;
            }
            for (var entry : handlers.entrySet()) {
                if (entry.getValue() instanceof WebSocketHandler
                        && !paths.add(normalizePath(entry.getKey().getPatternString()))
                        && !registration.hasLocalOrder()) {
                    needsLocalOrder = true;
                    break;
                }
            }
        }
        if (!needsLocalOrder) {
            return mappings;
        }

        // Dispatcher entries do not carry bean names. On a duplicate path, use the report's original local
        // bean order (including FactoryBean products), but do not add mappings to the held snapshot.
        List<SimpleUrlHandlerMapping> ordered = new ArrayList<>();
        for (var mapping : applicationContext
                .getBeansOfType(SimpleUrlHandlerMapping.class, false, false)
                .values()) {
            if (remaining.remove(mapping)) {
                ordered.add(mapping);
            }
        }
        for (var mapping : mappings) {
            if (remaining.remove(mapping)) {
                ordered.add(mapping);
            }
        }
        return ordered;
    }

    @Override
    public WebSocketTopologySnapshot topology() {
        List<WebSocketEndpointSnapshot> endpoints = endpoints();
        if (endpoints.isEmpty()) {
            return null;
        }
        return new WebSocketTopologySnapshot(
                FRAMEWORK, endpoints, List.of(), List.of(), null, false, CAPTURE_UNAVAILABLE);
    }

    private List<WebSocketEndpointSnapshot> endpoints() {
        List<WebSocketEndpointSnapshot> endpoints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SimpleUrlHandlerMapping mapping : orderedReportMappings()) {
            var handlers = mapping.getHandlerMap();
            if (handlers == null) {
                continue;
            }
            for (Map.Entry<?, ?> entry : handlers.entrySet()) {
                if (!(entry.getValue() instanceof WebSocketHandler handler)) {
                    continue;
                }
                String path = normalizePath(String.valueOf(entry.getKey()));
                String id = "handler:" + path;
                if (!seen.add(id)) {
                    continue;
                }
                endpoints.add(new WebSocketEndpointSnapshot(
                        id,
                        path,
                        "HANDLER",
                        handler.getClass().getName(),
                        subprotocols(handler),
                        false,
                        List.of(),
                        List.of(),
                        callbacks(handler),
                        null,
                        false));
            }
        }
        return endpoints;
    }

    private List<WebSocketCallbackSnapshot> callbacks(WebSocketHandler handler) {
        return List.of(WebSocketCallbackSnapshot.lifecycle(
                "ON_SESSION", handler.getClass().getName(), "handle"));
    }

    private List<String> subprotocols(WebSocketHandler handler) {
        List<String> protocols = handler.getSubProtocols();
        return protocols == null ? List.of() : List.copyOf(protocols);
    }

    static String normalizePath(String pattern) {
        if (pattern == null) {
            return null;
        }
        String path = pattern;
        if (path.endsWith("/**")) {
            path = path.substring(0, path.length() - 3);
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }
}
