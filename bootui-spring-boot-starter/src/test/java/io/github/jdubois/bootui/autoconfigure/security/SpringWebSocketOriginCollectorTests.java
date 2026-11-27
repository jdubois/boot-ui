package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.security.WebSocketOriginObservation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.sockjs.transport.handler.DefaultSockJsService;

class SpringWebSocketOriginCollectorTests {
    private static WebSocketHttpRequestHandler request(WebSocketHandler handler, String origin) {
        var request = new WebSocketHttpRequestHandler(handler);
        request.setHandshakeInterceptors(List.of(new OriginHandshakeInterceptor(List.of(origin))));
        return request;
    }

    @Test
    void nativeStompAndSockJsUseDeclarationsWithoutCallingTopologyOrHandlers() {
        try (var context = new GenericApplicationContext()) {
            var stomp = new SubProtocolWebSocketHandler(
                    new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel());
            var service = new DefaultSockJsService(mock(TaskScheduler.class));
            service.setAllowedOriginPatterns(List.of("https://*.example.com"));
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of(
                    "/native", request(new TextWebSocketHandler(), "*"),
                    "/stomp", request(stomp, "*"),
                    "/sockjs/**", new SockJsHttpRequestHandler(service, new TextWebSocketHandler())));
            context.registerBean("mapping", WebSocketHandlerMapping.class, () -> mapping);
            context.refresh();

            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.failed()).isFalse();
            assertThat(observation.complete()).isTrue();
            assertThat(observation.endpoints()).hasSize(3).allMatch(WebSocketOriginObservation.Endpoint::wildcard);
            assertThat(observation.endpoints()).allMatch(WebSocketOriginObservation.Endpoint::policyKnown);
            assertThat(observation.endpoints())
                    .extracting(WebSocketOriginObservation.Endpoint::path)
                    .contains("/native", "/stomp", "/sockjs/**");
        }
    }

    @Test
    void earlySingletonReferencesNeverInvokeTheirFactories() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory() {
            void registerEarlyReference(String name, org.springframework.beans.factory.ObjectFactory<?> supplier) {
                beforeSingletonCreation(name);
                addSingletonFactory(name, supplier);
            }

            void finishEarlyReference(String name) {
                afterSingletonCreation(name);
                destroySingleton(name);
            }
        };
        AtomicInteger callbacks = new AtomicInteger();
        try (var context = new GenericApplicationContext(factory)) {
            context.refresh();
            factory.registerEarlyReference("earlyMapping", () -> {
                callbacks.incrementAndGet();
                return new WebSocketHandlerMapping();
            });
            try {
                var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
                assertThat(callbacks).hasValue(0);
                assertThat(observation.complete()).isFalse();
            } finally {
                factory.finishEarlyReference("earlyMapping");
            }
        }
    }

    @Test
    void rootAndCatchAllRegistrationsAreNotLostOutsideTheHandlerMap() {
        try (var context = new GenericApplicationContext()) {
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of(
                    "/", request(new TextWebSocketHandler(), "*"),
                    "/*", request(new TextWebSocketHandler(), "*")));
            context.registerBean("mapping", WebSocketHandlerMapping.class, () -> mapping);
            context.refresh();

            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.complete()).isTrue();
            assertThat(observation.endpoints())
                    .extracting(WebSocketOriginObservation.Endpoint::path)
                    .containsExactlyInAnyOrder("/", "/*");
            assertThat(observation.endpoints()).allMatch(WebSocketOriginObservation.Endpoint::wildcard);
        }
    }

    @Test
    void distinctNativeRoutingPatternsKeepDistinctFindingIdentities() {
        try (var context = new GenericApplicationContext()) {
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of(
                    "/socket", request(new TextWebSocketHandler(), "*"),
                    "/socket/", request(new TextWebSocketHandler(), "*"),
                    "/socket/**", request(new TextWebSocketHandler(), "*")));
            context.registerBean("mapping", WebSocketHandlerMapping.class, () -> mapping);
            context.refresh();

            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.endpoints())
                    .extracting(WebSocketOriginObservation.Endpoint::identity)
                    .doesNotHaveDuplicates();
            var scanner = new io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAdvisorService(
                    () -> null, java.time.Clock.systemUTC());
            scanner.setWebSocketOriginsSupplier(() -> observation);
            assertThat(scanner.scan().results())
                    .singleElement()
                    .satisfies(result -> assertThat(result.violationCount()).isEqualTo(3));
        }
    }

    @Test
    void unrelatedLazyPrototypeAndFactoryDefinitionsDoNotLimitWebSocketCoverage() {
        AtomicInteger callbacks = new AtomicInteger();
        try (var context = new GenericApplicationContext()) {
            context.registerBean(
                    "lazy",
                    Object.class,
                    () -> {
                        callbacks.incrementAndGet();
                        return new Object();
                    },
                    definition -> definition.setLazyInit(true));
            context.registerBean(
                    "prototype",
                    Object.class,
                    () -> {
                        callbacks.incrementAndGet();
                        return new Object();
                    },
                    definition -> definition.setScope(BeanDefinition.SCOPE_PROTOTYPE));
            context.refresh();
            context.getBeanFactory().registerSingleton("factory", new FactoryBean<Object>() {
                @Override
                public Object getObject() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }

                @Override
                public Class<?> getObjectType() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }
            });
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.complete()).isTrue();
            assertThat(observation.endpoints()).isEmpty();
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void uninitializedMappingDefinitionsLimitCoverageWithoutCreatingBeans() {
        AtomicInteger callbacks = new AtomicInteger();
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            context.registerBean(
                    "lazyMapping",
                    WebSocketHandlerMapping.class,
                    () -> {
                        callbacks.incrementAndGet();
                        return new WebSocketHandlerMapping();
                    },
                    definition -> definition.setLazyInit(true));
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.complete()).isFalse();
            assertThat(observation.endpoints()).isEmpty();
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void customGettersLazyBeansAndFactoryCallbacksAreNeverInvoked() {
        AtomicInteger callbacks = new AtomicInteger();
        try (var context = new GenericApplicationContext()) {
            var mapping = new WebSocketHandlerMapping() {
                @Override
                public void start() {}

                @Override
                public void stop() {}

                @Override
                public Map<String, Object> getUrlMap() {
                    callbacks.incrementAndGet();
                    throw new AssertionError("Not passive");
                }
            };
            mapping.setUrlMap(Map.of(
                    "/known",
                    request(new TextWebSocketHandler(), "*"),
                    "/custom",
                    new WebSocketHttpRequestHandler(new TextWebSocketHandler()) {
                        @Override
                        public List<org.springframework.web.socket.server.HandshakeInterceptor>
                                getHandshakeInterceptors() {
                            callbacks.incrementAndGet();
                            throw new AssertionError("Not passive");
                        }
                    }));
            context.registerBean("mapping", WebSocketHandlerMapping.class, () -> mapping);
            context.registerBean(
                    "lazy",
                    TextWebSocketHandler.class,
                    () -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("Lazy bean initialized");
                    },
                    definition -> definition.setLazyInit(true));
            context.refresh();
            context.getBeanFactory().registerSingleton("factory", new FactoryBean<Object>() {
                @Override
                public Object getObject() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }

                @Override
                public Class<?> getObjectType() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }

                @Override
                public boolean isSingleton() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }
            });

            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(callbacks).hasValue(0);
            assertThat(observation.complete()).isFalse();
            assertThat(observation.endpoints()).anyMatch(e -> e.path().equals("/known") && e.wildcard());
            assertThat(observation.endpoints()).anyMatch(e -> e.path().equals("/custom") && !e.policyKnown());
        }
    }

    @Test
    void unreadableSiblingKeepsKnownDeclarationsAndReportsFailure() {
        try (var context = new GenericApplicationContext()) {
            var broken = new WebSocketHttpRequestHandler(new TextWebSocketHandler());
            ReflectionTestUtils.setField(broken, "interceptors", null);
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of("/known", request(new TextWebSocketHandler(), "*"), "/broken", broken));
            context.registerBean("mapping", WebSocketHandlerMapping.class, () -> mapping);
            context.refresh();
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.failed()).isTrue();
            assertThat(observation.endpoints()).anyMatch(WebSocketOriginObservation.Endpoint::wildcard);
        }
    }

    @Test
    void heldDispatcherMappingIsReadEvenWhenNotALocalSingleton() {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of("/held", request(new TextWebSocketHandler(), "*")));
            mapping.setApplicationContext(context);
            mapping.initApplicationContext();
            var dispatcher = new org.springframework.web.servlet.DispatcherServlet();
            ReflectionTestUtils.setField(dispatcher, "handlerMappings", List.of(mapping));
            context.getBeanFactory().registerSingleton("dispatcher", dispatcher);
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.endpoints())
                    .singleElement()
                    .satisfies(e -> assertThat(e.wildcard()).isTrue());
        }
    }

    @Test
    void customOriginGettersAndWrappedApplicationListsRemainUnknownWithoutCallbacks() {
        AtomicInteger callbacks = new AtomicInteger();
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var custom = new OriginHandshakeInterceptor(List.of("*")) {
                @Override
                public List<String> getAllowedOrigins() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }
            };
            var request = new WebSocketHttpRequestHandler(new TextWebSocketHandler());
            request.setHandshakeInterceptors(List.of(custom));
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of("/custom", request));
            mapping.setApplicationContext(context);
            mapping.initApplicationContext();
            context.getBeanFactory().registerSingleton("mapping", mapping);
            var dispatcher = new org.springframework.web.reactive.DispatcherHandler();
            List<Object> hostile = new java.util.AbstractList<>() {
                @Override
                public Object get(int index) {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }

                @Override
                public int size() {
                    callbacks.incrementAndGet();
                    throw new AssertionError();
                }
            };
            ReflectionTestUtils.setField(
                    dispatcher, "handlerMappings", java.util.Collections.unmodifiableList(hostile));
            context.getBeanFactory().registerSingleton("dispatcher", dispatcher);
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(callbacks).hasValue(0);
            assertThat(observation.failed()).isFalse();
            assertThat(observation.complete()).isFalse();
            assertThat(observation.endpoints()).singleElement().satisfies(e -> {
                assertThat(e.policyKnown()).isFalse();
                assertThat(e.wildcard()).isFalse();
            });
        }
    }

    @Test
    void reactivePresenceIsUnknownPolicyWithoutHandlerCallbacksOrCorsInference() {
        AtomicInteger callbacks = new AtomicInteger();
        try (var context = new GenericApplicationContext()) {
            org.springframework.web.reactive.socket.WebSocketHandler handler =
                    new org.springframework.web.reactive.socket.WebSocketHandler() {
                        @Override
                        public reactor.core.publisher.Mono<Void> handle(
                                org.springframework.web.reactive.socket.WebSocketSession session) {
                            callbacks.incrementAndGet();
                            throw new AssertionError();
                        }

                        @Override
                        public List<String> getSubProtocols() {
                            callbacks.incrementAndGet();
                            throw new AssertionError();
                        }
                    };
            var mapping = new org.springframework.web.reactive.handler.SimpleUrlHandlerMapping(
                    Map.of("/reactive", handler), 0);
            var cors = new org.springframework.web.cors.CorsConfiguration();
            cors.addAllowedOrigin("*");
            mapping.setCorsConfigurations(Map.of("/**", cors));
            context.registerBean(
                    "mapping", org.springframework.web.reactive.handler.SimpleUrlHandlerMapping.class, () -> mapping);
            context.refresh();
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.endpoints()).singleElement().satisfies(e -> {
                assertThat(e.path()).isEqualTo("/reactive");
                assertThat(e.policyKnown()).isFalse();
                assertThat(e.wildcard()).isFalse();
            });
            assertThat(observation.complete()).isFalse();
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void disabledPanelIsUnknownAndCaptureDisabledDoesNotHideDeclarations() {
        try (var context = new GenericApplicationContext()) {
            var mapping = new WebSocketHandlerMapping();
            mapping.setUrlMap(Map.of("/ws", request(new TextWebSocketHandler(), "*")));
            context.registerBean("mapping", WebSocketHandlerMapping.class, () -> mapping);
            context.refresh();
            var properties = new BootUiProperties();
            properties.getWebsockets().setEnabled(false); // Capture only, not declaration visibility.
            assertThat(new SpringWebSocketOriginCollector(context, properties)
                            .collect()
                            .endpoints())
                    .hasSize(1);
            var panel = new BootUiProperties.Panel();
            panel.setEnabled(false);
            properties.getPanels().put("websockets", panel);
            var disabled = new SpringWebSocketOriginCollector(context, properties).collect();
            assertThat(disabled.endpoints()).isEmpty();
            assertThat(disabled.complete()).isFalse();
        }
    }

    @Test
    void absentOptionalLibrariesNeedNoLoadingAndNoEndpointsIsNotPolicyEvidence() throws Exception {
        try (var loader =
                        new FilteredClassLoader("org.springframework.web.socket", "org.springframework.web.reactive");
                var context = new GenericApplicationContext()) {
            context.setClassLoader(loader);
            context.refresh();
            var observation = new SpringWebSocketOriginCollector(context, new BootUiProperties()).collect();
            assertThat(observation.complete()).isTrue();
            assertThat(observation.endpoints()).isEmpty();
            var hints = new RuntimeHints();
            SpringWebSocketOriginHints.register(hints, loader);
            assertThat(hints.reflection().getTypeHint(OriginHandshakeInterceptor.class))
                    .isNull();
        }
    }

    @Test
    void hintsRetainPrivateFieldsRatherThanApplicationCallbackMethods() {
        var hints = new RuntimeHints();
        SpringWebSocketOriginHints.register(hints, getClass().getClassLoader());
        for (String name : SpringWebSocketOriginCollector.FIELD_TYPES) {
            assertThat(hints.reflection()
                            .getTypeHint(org.springframework.aot.hint.TypeReference.of(name))
                            .getMemberCategories())
                    .contains(MemberCategory.ACCESS_DECLARED_FIELDS)
                    .doesNotContain(MemberCategory.INVOKE_PUBLIC_METHODS);
        }
    }
}
