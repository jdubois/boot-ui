package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.HandlerMethod;
import org.springframework.messaging.handler.invocation.AbstractMethodMessageHandler;
import org.springframework.messaging.simp.SimpMessageMappingInfo;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.socket.sockjs.SockJsService;
import org.springframework.web.socket.sockjs.support.AbstractSockJsService;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;

/**
 * Reads the Spring servlet WebSocket topology from Spring's own registries.
 *
 * <p>Endpoints come from every {@link WebSocketHandlerMapping} bean — the mapping Spring registers for
 * both {@code @EnableWebSocket} handlers and {@code @EnableWebSocketMessageBroker} STOMP endpoints — so
 * BootUI does not have to guess bean names or reimplement path matching. STOMP destinations, broker
 * prefixes, and application destination prefixes come from {@link SimpAnnotationMethodMessageHandler} and
 * {@link AbstractBrokerMessageHandler}.</p>
 *
 * <p>Resolving the topology is a pure read: it never triggers a handshake, opens a session, or sends a
 * frame, so the panel stays network-free on render. This class is the only importer of
 * {@code org.springframework.web.socket.*} metadata types in the read path and is wired only when those
 * classes are present.</p>
 */
public class SpringWebSocketMetadataProvider implements WebSocketMetadataProvider {

    public static final String FRAMEWORK = "spring-websocket";
    static final String KIND_STOMP = "STOMP";
    static final String KIND_HANDLER = "HANDLER";

    private final ApplicationContext applicationContext;

    public SpringWebSocketMetadataProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public WebSocketTopologySnapshot topology() {
        List<WebSocketEndpointSnapshot> endpoints = endpoints();
        if (endpoints.isEmpty()) {
            return null;
        }
        boolean stompPresent = endpoints.stream().anyMatch(endpoint -> KIND_STOMP.equals(endpoint.kind()));
        return new WebSocketTopologySnapshot(
                FRAMEWORK,
                endpoints,
                brokerPrefixes(),
                applicationDestinationPrefixes(),
                userDestinationPrefix(),
                stompPresent,
                stompPresent
                        ? null
                        : "Frame capture needs a decoration seam. Spring only offers one for STOMP endpoints "
                                + "(@EnableWebSocketMessageBroker), so native WebSocketHandler endpoints are "
                                + "described but not observed.");
    }

    /** Returns the endpoint id serving {@code path}, or {@code null} when no endpoint matches. */
    public String endpointIdFor(String path) {
        if (path == null) {
            return null;
        }
        String best = null;
        String bestPath = null;
        for (WebSocketEndpointSnapshot endpoint : endpoints()) {
            String candidate = endpoint.path();
            if (candidate == null || !path.startsWith(candidate)) {
                continue;
            }
            if (bestPath == null || candidate.length() > bestPath.length()) {
                best = endpoint.id();
                bestPath = candidate;
            }
        }
        return best;
    }

    private List<WebSocketEndpointSnapshot> endpoints() {
        List<WebSocketEndpointSnapshot> endpoints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, List<WebSocketCallbackSnapshot>> stompCallbacks = stompCallbacks();
        for (WebSocketHandlerMapping mapping : beans(WebSocketHandlerMapping.class)) {
            for (Map.Entry<String, ?> entry : mapping.getUrlMap().entrySet()) {
                WebSocketEndpointSnapshot endpoint = endpoint(entry.getKey(), entry.getValue(), stompCallbacks);
                if (endpoint != null && seen.add(endpoint.id())) {
                    endpoints.add(endpoint);
                }
            }
        }
        return endpoints;
    }

    private WebSocketEndpointSnapshot endpoint(
            String pattern, Object handler, Map<String, List<WebSocketCallbackSnapshot>> stompCallbacks) {
        String path = normalizePath(pattern);
        WebSocketHandler webSocketHandler;
        boolean sockJs = false;
        List<String> allowedOrigins = List.of();
        List<String> interceptors = List.of();
        if (handler instanceof SockJsHttpRequestHandler sockJsHandler) {
            webSocketHandler = sockJsHandler.getWebSocketHandler();
            sockJs = true;
            allowedOrigins = sockJsAllowedOrigins(sockJsHandler.getSockJsService());
        } else if (handler instanceof WebSocketHttpRequestHandler requestHandler) {
            webSocketHandler = requestHandler.getWebSocketHandler();
            interceptors = interceptorNames(requestHandler.getHandshakeInterceptors());
            allowedOrigins = handshakeAllowedOrigins(requestHandler.getHandshakeInterceptors());
        } else {
            return null;
        }
        WebSocketHandler target = WebSocketHandlerDecorator.unwrap(webSocketHandler);
        boolean stomp = target instanceof SubProtocolWebSocketHandler;
        String kind = stomp ? KIND_STOMP : KIND_HANDLER;
        List<WebSocketCallbackSnapshot> callbacks = stomp ? flatten(stompCallbacks) : handlerCallbacks(target);
        return new WebSocketEndpointSnapshot(
                kind.toLowerCase(java.util.Locale.ROOT) + ":" + path,
                path,
                kind,
                target.getClass().getName(),
                subprotocols(target),
                sockJs,
                allowedOrigins,
                interceptors,
                callbacks,
                null,
                stomp);
    }

    private List<WebSocketCallbackSnapshot> handlerCallbacks(WebSocketHandler handler) {
        String declaringClass = handler.getClass().getName();
        return List.of(
                WebSocketCallbackSnapshot.lifecycle("ON_OPEN", declaringClass, "afterConnectionEstablished"),
                WebSocketCallbackSnapshot.lifecycle("ON_MESSAGE", declaringClass, "handleMessage"),
                WebSocketCallbackSnapshot.lifecycle("ON_ERROR", declaringClass, "handleTransportError"),
                WebSocketCallbackSnapshot.lifecycle("ON_CLOSE", declaringClass, "afterConnectionClosed"));
    }

    /** STOMP destinations declared with {@code @MessageMapping}/{@code @SubscribeMapping}. */
    private Map<String, List<WebSocketCallbackSnapshot>> stompCallbacks() {
        Map<String, List<WebSocketCallbackSnapshot>> byDestination = new java.util.LinkedHashMap<>();
        for (SimpAnnotationMethodMessageHandler handler : beans(SimpAnnotationMethodMessageHandler.class)) {
            Map<SimpMessageMappingInfo, HandlerMethod> handlerMethods = handlerMethods(handler);
            for (Map.Entry<SimpMessageMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                SimpMessageType messageType =
                        entry.getKey().getMessageTypeMessageCondition().getMessageType();
                String type = messageType == SimpMessageType.SUBSCRIBE ? "SUBSCRIBE_MAPPING" : "MESSAGE_MAPPING";
                HandlerMethod method = entry.getValue();
                for (String destination :
                        new TreeSet<>(entry.getKey().getDestinationConditions().getPatterns())) {
                    byDestination
                            .computeIfAbsent(destination, key -> new ArrayList<>())
                            .add(new WebSocketCallbackSnapshot(
                                    type,
                                    destination,
                                    method.getBeanType().getName(),
                                    method.getMethod().getName(),
                                    null));
                }
            }
        }
        return byDestination;
    }

    @SuppressWarnings("unchecked")
    private Map<SimpMessageMappingInfo, HandlerMethod> handlerMethods(SimpAnnotationMethodMessageHandler handler) {
        return ((AbstractMethodMessageHandler<SimpMessageMappingInfo>) handler).getHandlerMethods();
    }

    private List<WebSocketCallbackSnapshot> flatten(Map<String, List<WebSocketCallbackSnapshot>> byDestination) {
        List<WebSocketCallbackSnapshot> callbacks = new ArrayList<>();
        byDestination.values().forEach(callbacks::addAll);
        callbacks.sort(Comparator.comparing(callback -> callback.destination() == null ? "" : callback.destination()));
        return callbacks;
    }

    private List<String> brokerPrefixes() {
        Set<String> prefixes = new java.util.TreeSet<>();
        for (AbstractBrokerMessageHandler handler : beans(AbstractBrokerMessageHandler.class)) {
            Collection<String> destinationPrefixes = handler.getDestinationPrefixes();
            if (destinationPrefixes != null) {
                prefixes.addAll(destinationPrefixes);
            }
        }
        return List.copyOf(prefixes);
    }

    private List<String> applicationDestinationPrefixes() {
        Set<String> prefixes = new java.util.TreeSet<>();
        for (SimpAnnotationMethodMessageHandler handler : beans(SimpAnnotationMethodMessageHandler.class)) {
            Collection<String> destinationPrefixes = handler.getDestinationPrefixes();
            if (destinationPrefixes != null) {
                prefixes.addAll(destinationPrefixes);
            }
        }
        return List.copyOf(prefixes);
    }

    private String userDestinationPrefix() {
        for (org.springframework.messaging.simp.user.DefaultUserDestinationResolver resolver :
                beans(org.springframework.messaging.simp.user.DefaultUserDestinationResolver.class)) {
            String prefix = resolver.getDestinationPrefix();
            if (prefix != null && !prefix.isBlank()) {
                return prefix;
            }
        }
        return null;
    }

    private List<String> subprotocols(WebSocketHandler handler) {
        if (handler instanceof SubProtocolCapable capable) {
            List<String> protocols = capable.getSubProtocols();
            return protocols == null ? List.of() : List.copyOf(protocols);
        }
        return List.of();
    }

    private List<String> interceptorNames(List<HandshakeInterceptor> interceptors) {
        if (interceptors == null || interceptors.isEmpty()) {
            return List.of();
        }
        return interceptors.stream()
                .map(interceptor -> interceptor.getClass().getName())
                .toList();
    }

    private List<String> handshakeAllowedOrigins(List<HandshakeInterceptor> interceptors) {
        if (interceptors == null) {
            return List.of();
        }
        for (HandshakeInterceptor interceptor : interceptors) {
            if (interceptor instanceof OriginHandshakeInterceptor origin) {
                return merge(origin.getAllowedOrigins(), origin.getAllowedOriginPatterns());
            }
        }
        return List.of();
    }

    private List<String> sockJsAllowedOrigins(SockJsService service) {
        if (service instanceof AbstractSockJsService sockJsService) {
            return merge(sockJsService.getAllowedOrigins(), sockJsService.getAllowedOriginPatterns());
        }
        return List.of();
    }

    private List<String> merge(Collection<String> origins, Collection<String> patterns) {
        Set<String> merged = new LinkedHashSet<>();
        if (origins != null) {
            merged.addAll(origins);
        }
        if (patterns != null) {
            merged.addAll(patterns);
        }
        return List.copyOf(merged);
    }

    private <T> List<T> beans(Class<T> type) {
        try {
            return List.copyOf(
                    applicationContext.getBeansOfType(type, false, false).values());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** Strips SockJS/AntPath suffixes so {@code /ws/**} and {@code /ws} report the same endpoint path. */
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
