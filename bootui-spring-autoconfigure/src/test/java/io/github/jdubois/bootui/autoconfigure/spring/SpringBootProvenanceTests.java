package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.context.ShutdownEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.env.EnvironmentEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.management.HeapDumpWebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.web.client.RestClient;

class SpringBootProvenanceTests {
    private final WebApplicationContextRunner actuator = new WebApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment()
                    .setConversionService(new org.springframework.boot.convert.ApplicationConversionService()))
            .withConfiguration(AutoConfigurations.of(
                    EndpointAutoConfiguration.class,
                    WebEndpointAutoConfiguration.class,
                    ShutdownEndpointAutoConfiguration.class,
                    HeapDumpWebEndpointAutoConfiguration.class,
                    EnvironmentEndpointAutoConfiguration.class));
    private final WebApplicationContextRunner mvc = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TomcatServletWebServerAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    ErrorMvcAutoConfiguration.class));

    @Test
    void realActuatorEndpointDefaultsAndExplicitReadWriteGrants() {
        actuator.withPropertyValues("management.endpoints.web.exposure.include=heapdump,shutdown")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().known(ENDPOINTS)).isTrue();
                    assertThat(new DangerousActuatorEndpointsAccessibleRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("PASS");
                });
        actuator.withPropertyValues(
                        "management.endpoints.web.exposure.include=heapdump,shutdown",
                        "management.endpoint.heapdump.access=read-only",
                        "management.endpoint.shutdown.access=unrestricted")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(ENDPOINTS, Set.class))
                            .contains("heapdump", "shutdown");
                    assertThat(new DangerousActuatorEndpointsAccessibleRule()
                                    .evaluate(snapshot)
                                    .violationCount())
                            .isEqualTo(2);
                });
    }

    @Test
    void realActuatorDoesNotRequireReadingAnEndpointSupplier() {
        actuator.withPropertyValues("management.endpoints.web.exposure.include=env")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new SensitiveActuatorEndpointsExposedRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                });
    }

    @Test
    void actuatorAccessAndLegacyAliasesMatchBootTypedConversion() {
        for (String property : new String[] {
            "management.endpoint.heapdump.access=readonly",
            "management.endpoint.heapdump.enabled=on",
            "management.endpoints.access.default=readonly",
            "management.endpoints.enabled-by-default=on"
        }) {
            actuator.withPropertyValues("management.endpoints.web.exposure.include=heapdump", property)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var access = context.getBean(
                                        org.springframework.boot.actuate.endpoint.EndpointAccessResolver.class)
                                .accessFor(
                                        org.springframework.boot.actuate.endpoint.EndpointId.of("heapdump"),
                                        org.springframework.boot.actuate.endpoint.Access.NONE);
                        assertThat(access)
                                .isEqualTo(
                                        property.endsWith("readonly")
                                                ? org.springframework.boot.actuate.endpoint.Access.READ_ONLY
                                                : org.springframework.boot.actuate.endpoint.Access.UNRESTRICTED);
                        var result = new DangerousActuatorEndpointsAccessibleRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false));
                        assertThat(result.status()).as(property).isEqualTo("VIOLATION");
                        assertThat(result.violationCount()).isEqualTo(1);
                    });
        }
        actuator.withPropertyValues(
                        "management.endpoints.web.exposure.include=heapdump,shutdown",
                        "management.endpoint.heapdump.enabled=on",
                        "management.endpoint.shutdown.enabled=on",
                        "management.endpoints.access.max-permitted=readonly")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = new DangerousActuatorEndpointsAccessibleRule()
                            .evaluate(SpringInventory.discover(
                                    context.getBeanFactory(), context.getEnvironment(), false));
                    assertThat(result.status()).isEqualTo("VIOLATION");
                    assertThat(result.violationCount()).isEqualTo(1); // heapdump reads, no shutdown writes
                });
    }

    @Test
    void emptyActuatorSettingsFollowBootTypedInheritanceWithoutFalseConflicts() {
        for (String[] properties : new String[][] {
            {"management.endpoints.access.default=unrestricted", "management.endpoint.heapdump.access="},
            {"management.endpoints.enabled-by-default=on", "management.endpoint.heapdump.enabled="},
            {"management.endpoint.heapdump.access=", "management.endpoint.heapdump.enabled=on"},
            {"management.endpoint.heapdump.access=readonly", "management.endpoint.heapdump.enabled="},
            {"management.endpoints.access.default=", "management.endpoints.enabled-by-default=on"},
            {"management.endpoints.access.default=unrestricted", "management.endpoints.enabled-by-default="}
        }) {
            actuator.withPropertyValues("management.endpoints.web.exposure.include=heapdump")
                    .withPropertyValues(properties)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var access = context.getBean(
                                        org.springframework.boot.actuate.endpoint.EndpointAccessResolver.class)
                                .accessFor(
                                        org.springframework.boot.actuate.endpoint.EndpointId.of("heapdump"),
                                        org.springframework.boot.actuate.endpoint.Access.NONE);
                        assertThat(access).isNotEqualTo(org.springframework.boot.actuate.endpoint.Access.NONE);
                        var result = new DangerousActuatorEndpointsAccessibleRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false));
                        assertThat(result.status())
                                .as(String.join(", ", properties))
                                .isEqualTo("VIOLATION");
                        assertThat(result.violationCount()).isEqualTo(1);
                    });
        }
        actuator.withPropertyValues(
                        "management.endpoints.web.exposure.include=heapdump",
                        "management.endpoints.access.default=none",
                        "management.endpoint.heapdump.access=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = new DangerousActuatorEndpointsAccessibleRule()
                            .evaluate(SpringInventory.discover(
                                    context.getBeanFactory(), context.getEnvironment(), false));
                    assertThat(result.status()).isEqualTo("PASS");
                });
    }

    @Test
    void customEndpointAccessResolverIsUnknownAndIsNeverCalledByScanner() {
        var calls = new AtomicInteger();
        actuator.withPropertyValues("management.endpoints.web.exposure.include=env")
                .withBean(
                        "customAccess",
                        org.springframework.boot.actuate.endpoint.EndpointAccessResolver.class,
                        () -> (id, defaults) -> {
                            calls.incrementAndGet();
                            return org.springframework.boot.actuate.endpoint.Access.NONE;
                        })
                .run(context -> {
                    int before = calls.get();
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new SensitiveActuatorEndpointsExposedRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(calls).hasValue(before);
                });
        actuator.withPropertyValues("management.endpoints.web.exposure.include=env")
                .withBean(
                        "customAccess",
                        org.springframework.boot.actuate.endpoint.EndpointAccessResolver.class,
                        () ->
                                new org.springframework.boot.actuate.autoconfigure.endpoint
                                        .PropertiesEndpointAccessResolver(
                                        new org.springframework.mock.env.MockEnvironment()
                                                .withProperty("management.endpoints.access.default", "NONE")))
                .run(context -> assertThat(new SensitiveActuatorEndpointsExposedRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false))
                                .status())
                        .isEqualTo("SKIPPED"));
    }

    @Test
    void originAndFallbackErrorAdviceHasRealBootMvcProvenance() {
        mvc.withPropertyValues("spring.web.error.include-message=on-param").run(context -> {
            assertThat(context).hasNotFailed();
            SpringContext snapshot =
                    SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
            assertThat(snapshot.observations().yes(BOOT_WEB_SERVER)).isTrue();
            assertThat(new ResponseCompressionDisabledRule().evaluate(snapshot).status())
                    .isEqualTo("VIOLATION");
            assertThat(new Http2DisabledRule().evaluate(snapshot).status()).isEqualTo("VIOLATION");
            assertThat(new ErrorDetailsExposedRule().evaluate(snapshot).status())
                    .isEqualTo("VIOLATION");
        });
    }

    @Test
    void customServerConfigurationDoesNotInventDisabledRuntime() {
        AtomicInteger customizations = new AtomicInteger();
        mvc.withBean(
                        "customServer",
                        WebServerFactoryCustomizer.class,
                        () -> factory -> customizations.incrementAndGet())
                .run(context -> {
                    int before = customizations.get();
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new ResponseCompressionDisabledRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(customizations).hasValue(before);
                });
    }

    @Test
    void tomcatVirtualThreadCapRequiresObservedRegisteredBootCustomization() {
        // A mock servlet context is deliberately treated as a WAR by Boot and skips the embedded
        // Tomcat customization. Reactive wiring exercises the real shared customization without
        // opening a listening socket.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.tomcat.autoconfigure.reactive.TomcatReactiveWebServerAutoConfiguration
                                .class))
                .withPropertyValues(
                        "spring.threads.virtual.enabled=" + (Runtime.version().feature() >= 21),
                        "server.tomcat.threads.max=200")
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), true);
                    var server = context.getBean(
                            org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory.class);
                    assertThat(snapshot.observations().known(BOOT_WEB_SERVER)).isTrue();
                    if (Runtime.version().feature() >= 21)
                        assertThat(snapshot.observations().known(TOMCAT_VIRTUAL_EXECUTOR))
                                .as(
                                        "registered protocol customizers: %s; connector customizers: %s; context customizers: %s",
                                        server.getProtocolHandlerCustomizers().stream()
                                                .map(c -> c.getClass().getName())
                                                .toList(),
                                        server.getConnectorCustomizers().stream()
                                                .map(c -> c.getClass().getName())
                                                .toList(),
                                        server.getContextCustomizers().stream()
                                                .map(c -> c.getClass().getName())
                                                .toList())
                                .isTrue();
                    assertThat(new RedundantTomcatThreadsRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo(Runtime.version().feature() >= 21 ? "VIOLATION" : "SKIPPED");
                });
    }

    @Test
    void reactiveBootOriginsAndFallbackErrorsUseTheSameQualifiedBoundaries() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.tomcat.autoconfigure.reactive.TomcatReactiveWebServerAutoConfiguration
                                .class,
                        org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration.class,
                        org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration.class))
                .withPropertyValues("spring.web.error.include-message=on-param")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), true);
                    assertThat(new ResponseCompressionDisabledRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                    assertThat(new ErrorDetailsExposedRule().evaluate(snapshot).status())
                            .isEqualTo("VIOLATION");
                });
    }

    @Test
    void jackson3AndBootBuilderConfigurationAreDistinguishedFromCustomBeans() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(JacksonAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withPropertyValues("spring.jackson.use-jackson2-defaults=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new Jackson2DefaultsCompatibilityRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                    assertThat(new HttpClientTimeoutsUnsetRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                });
        new ApplicationContextRunner()
                .withBean("custom", RestClient.class, RestClient::create)
                .run(context -> assertThat(new HttpClientTimeoutsUnsetRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false))
                                .status())
                        .isEqualTo("SKIPPED"));
    }

    @Test
    void optionalIntegrationAbsenceKeepsScanningAvailable() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(
                        "jakarta.persistence",
                        "org.springframework.orm",
                        "reactor",
                        "io.r2dbc",
                        "com.zaxxer.hikari",
                        "org.springframework.boot.actuate",
                        "org.springframework.boot.tomcat"))
                .run(context -> {
                    var report = new SpringScanner(
                                    context.getBeanFactory(), context.getEnvironment(), false, Clock.systemUTC())
                            .scan();
                    assertThat(report.scan().status()).isEqualTo("SCANNED");
                    assertThat(report.rulesEvaluated()).isEqualTo(38);
                });
    }

    @Test
    void bootServiceGroupBindingDoesNotLetOneCompleteGroupHideAnother() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.http.client.autoconfigure.service
                                .HttpServiceClientPropertiesAutoConfiguration.class))
                .withPropertyValues(
                        "spring.http.serviceclient.complete.connect-timeout=2s",
                        "spring.http.serviceclient.complete.read-timeout=3s",
                        "spring.http.serviceclient.partial.connect-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().yes(HTTP_SERVICE_GROUPS)).isTrue();
                    var result = new HttpClientTimeoutsUnsetRule().evaluate(snapshot);
                    assertThat(result.status()).isEqualTo("VIOLATION");
                    assertThat(result.violationCount()).isEqualTo(1);
                });
    }
}
