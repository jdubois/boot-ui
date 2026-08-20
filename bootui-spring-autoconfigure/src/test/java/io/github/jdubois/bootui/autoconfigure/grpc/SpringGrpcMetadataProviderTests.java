package io.github.jdubois.bootui.autoconfigure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.GrpcChannelSnapshot;
import io.github.jdubois.bootui.spi.GrpcMethodSnapshot;
import io.github.jdubois.bootui.spi.GrpcMethodType;
import io.github.jdubois.bootui.spi.GrpcRegistrySnapshot;
import io.github.jdubois.bootui.spi.GrpcServerSnapshot;
import io.github.jdubois.bootui.spi.GrpcServiceSnapshot;
import io.github.jdubois.bootui.spi.GrpcSetting;
import io.github.jdubois.bootui.spi.GrpcTransportSecurity;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies that the Spring gRPC registry is described from beans and configuration only. Every fixture here is
 * built in-process: no server is started, no channel is created and no name is resolved, which is exactly the
 * guarantee the panel makes.
 */
class SpringGrpcMetadataProviderTests {

    @Test
    void reportsUnavailableWithoutDescribingAnythingWhenGrpcIsAbsent() {
        SpringGrpcMetadataProvider provider =
                new SpringGrpcMetadataProvider(new GenericApplicationContext(), new MockEnvironment(), false, "absent");

        assertThat(provider.available()).isFalse();
        assertThat(provider.unavailableReason()).isEqualTo("absent");
        assertThat(provider.integration()).isNull();
        assertThat(provider.registry()).isEqualTo(GrpcRegistrySnapshot.EMPTY);
    }

    @Test
    void describesServerServicesAndMethodsFromBindableServiceBeans() {
        GrpcRegistrySnapshot registry = registry(
                Map.of("spring.grpc.server.port", "9090", "spring.grpc.server.address", "0.0.0.0"),
                Map.of("inventoryService", service("shop.Inventory")));

        assertThat(registry.servers()).hasSize(1);
        GrpcServerSnapshot server = registry.servers().get(0);
        assertThat(server.id()).isEqualTo("spring-grpc-server");
        assertThat(server.address()).isEqualTo("0.0.0.0");
        assertThat(server.port()).isEqualTo(9090);
        assertThat(server.security()).isEqualTo(GrpcTransportSecurity.PLAINTEXT);
        assertThat(server.services()).hasSize(1);
        GrpcServiceSnapshot service = server.services().get(0);
        assertThat(service.name()).isEqualTo("shop.Inventory");
        assertThat(service.implementationClass()).isEqualTo(TestService.class.getName());
        // The registry is reported in whatever order the binding exposes; the engine owns display ordering.
        assertThat(service.methods()).extracting(GrpcMethodSnapshot::name).containsExactlyInAnyOrder("Get", "Watch");
        assertThat(service.methods())
                .extracting(GrpcMethodSnapshot::fullName)
                .containsExactlyInAnyOrder("shop.Inventory/Get", "shop.Inventory/Watch");
        assertThat(service.methods())
                .extracting(GrpcMethodSnapshot::type)
                .containsExactlyInAnyOrder(GrpcMethodType.UNARY, GrpcMethodType.SERVER_STREAMING);
    }

    @Test
    void reportsNoServerWhenNeitherServicesNorServerConfigurationExist() {
        assertThat(registry(Map.of("spring.grpc.client.channel.billing.target", "static://localhost:9090"), Map.of())
                        .servers())
                .isEmpty();
    }

    @Test
    void readsServerTransportSecurityAndLimitsFromConfiguration() {
        GrpcRegistrySnapshot registry = registry(
                Map.of(
                        "spring.grpc.server.port", "9090",
                        "spring.grpc.server.ssl.bundle", "server",
                        "spring.grpc.server.reflection.enabled", "true",
                        "spring.grpc.server.inbound.message.max-size", "8MB",
                        "spring.grpc.server.inbound.metadata.max-size", "16KB",
                        "spring.grpc.server.keepalive.time", "30s",
                        "spring.grpc.server.health.enabled", "true"),
                Map.of());

        GrpcServerSnapshot server = registry.servers().get(0);
        assertThat(server.security()).isEqualTo(GrpcTransportSecurity.TLS);
        assertThat(server.reflectionEnabled()).isTrue();
        assertThat(server.maxInboundMessageSize()).isEqualTo(8L * 1024 * 1024);
        assertThat(server.maxInboundMetadataSize()).isEqualTo(16L * 1024);
        assertThat(server.keepAlive()).extracting(GrpcSetting::name).contains("Time");
        assertThat(server.settings()).extracting(GrpcSetting::name).contains("Health service", "SSL bundle");
    }

    @Test
    void prefersInProcessAndDomainSocketAddressesOverTheConfiguredAddress() {
        assertThat(registry(Map.of("spring.grpc.server.inprocess.name", "sample"), Map.of())
                        .servers()
                        .get(0)
                        .address())
                .isEqualTo("in-process:sample");
        assertThat(registry(Map.of("spring.grpc.server.netty.domain-socket-path", "/tmp/grpc.sock"), Map.of())
                        .servers()
                        .get(0)
                        .address())
                .isEqualTo("unix:/tmp/grpc.sock");
        assertThat(registry(Map.of("spring.grpc.server.port", "9090"), Map.of())
                        .servers()
                        .get(0)
                        .address())
                .isEqualTo("*");
    }

    @Test
    void describesEachConfiguredChannelWithItsOwnIdentityAndSecurity() {
        GrpcRegistrySnapshot registry = registry(
                Map.of(
                        "spring.grpc.client.channel.billing.target", "static://localhost:9090",
                        "spring.grpc.client.channel.billing.ssl.enabled", "false",
                        "spring.grpc.client.channel.billing.default.load-balancing-policy", "round_robin",
                        "spring.grpc.client.channel.billing.inbound.message.max-size", "4MB",
                        "spring.grpc.client.channel.secure.target", "dns:///payments.internal:443",
                        "spring.grpc.client.channel.secure.ssl.bundle", "client",
                        "spring.grpc.client.channel.secure.service-config.retry-policy.max-attempts", "3",
                        "spring.grpc.client.channel.unknown.target", "negotiated:9090"),
                Map.of());

        assertThat(registry.channels())
                .extracting(GrpcChannelSnapshot::name)
                .containsExactly("billing", "secure", "unknown");
        GrpcChannelSnapshot billing = registry.channels().get(0);
        assertThat(billing.target()).isEqualTo("static://localhost:9090");
        assertThat(billing.security()).isEqualTo(GrpcTransportSecurity.PLAINTEXT);
        assertThat(billing.loadBalancingPolicy()).isEqualTo("round_robin");
        assertThat(billing.maxInboundMessageSize()).isEqualTo(4L * 1024 * 1024);
        assertThat(billing.retryEnabled()).isNull();
        GrpcChannelSnapshot secure = registry.channels().get(1);
        assertThat(secure.security()).isEqualTo(GrpcTransportSecurity.TLS);
        assertThat(secure.retryEnabled()).isTrue();
        assertThat(registry.channels().get(2).security()).isEqualTo(GrpcTransportSecurity.UNKNOWN);
    }

    @Test
    void unwrapsQuotedChannelNames() {
        GrpcRegistrySnapshot registry = registry(
                Map.of("spring.grpc.client.channel.\"orders.eu\".target", "static://localhost:9091"), Map.of());

        assertThat(registry.channels()).extracting(GrpcChannelSnapshot::name).containsExactly("orders.eu");
    }

    @Test
    void warnsInsteadOfFailingWhenAServiceBeanCannotBeDescribed() {
        GrpcRegistrySnapshot registry = registry(
                Map.of("spring.grpc.server.port", "9090"),
                Map.of("brokenService", new BrokenService(), "inventoryService", service("shop.Inventory")));

        assertThat(registry.warnings())
                .anySatisfy(warning -> assertThat(warning).contains(BrokenService.class.getName()));
        assertThat(registry.servers().get(0).services())
                .extracting(GrpcServiceSnapshot::name)
                .containsExactly("shop.Inventory");
    }

    @Test
    void keepsOnlyTheFirstDefinitionOfADuplicateServiceName() {
        GrpcRegistrySnapshot registry = registry(
                Map.of("spring.grpc.server.port", "9090"),
                Map.of("first", service("shop.Inventory"), "second", service("shop.Inventory")));

        assertThat(registry.servers().get(0).services()).hasSize(1);
    }

    @Test
    void readsRetryFromTheServiceConfigMethodPathSpringBootActuallyBinds() {
        GrpcRegistrySnapshot registry = registry(
                Map.of(
                        "spring.grpc.client.channel.billing.target", "static://localhost:9090",
                        "spring.grpc.client.channel.billing.service-config.method[0].retry-policy.max-attempts", "3"),
                Map.of());

        assertThat(registry.channels().get(0).retryEnabled()).isTrue();
    }

    @Test
    void findsAChannelConfiguredThroughRelaxedEnvironmentVariableBinding() {
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("SPRING_GRPC_CLIENT_CHANNEL_BILLING_TARGET", "static://localhost:9090")));
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            GrpcRegistrySnapshot registry = new SpringGrpcMetadataProvider(context, environment, true, null).registry();
            assertThat(registry.channels())
                    .extracting(GrpcChannelSnapshot::name)
                    .containsExactly("billing");
        } finally {
            context.close();
        }
    }

    @Test
    void infersReflectionFromTheRegisteredReflectionServiceWhenNoPropertyIsSet() {
        GrpcRegistrySnapshot registry = registry(
                Map.of("spring.grpc.server.port", "9090"),
                Map.of("reflectionService", service("grpc.reflection.v1.ServerReflection")));

        assertThat(registry.servers().get(0).reflectionEnabled()).isTrue();
    }

    @Test
    void treatsAnExplicitlyDisabledReflectionPropertyAsAuthoritative() {
        GrpcRegistrySnapshot registry = registry(
                Map.of("spring.grpc.server.port", "9090", "spring.grpc.server.reflection.enabled", "false"),
                Map.of("reflectionService", service("grpc.reflection.v1.ServerReflection")));

        assertThat(registry.servers().get(0).reflectionEnabled()).isFalse();
    }

    @Test
    void describesEachServiceBeanOnlyOnceAcrossRepeatedReads() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.grpc.server.port", "9090");
        CountingService counting = new CountingService("shop.Inventory");
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("countingService", BindableService.class, () -> counting);
        context.refresh();
        try {
            SpringGrpcMetadataProvider provider = new SpringGrpcMetadataProvider(context, environment, true, null);
            provider.registry();
            provider.registry();
            provider.registry();

            assertThat(counting.bindCount).isEqualTo(1);
        } finally {
            context.close();
        }
    }

    private static GrpcRegistrySnapshot registry(Map<String, String> properties, Map<String, BindableService> beans) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        GenericApplicationContext context = new GenericApplicationContext();
        beans.forEach((name, bean) -> context.registerBean(name, BindableService.class, () -> bean));
        context.refresh();
        try {
            return new SpringGrpcMetadataProvider(context, environment, true, null).registry();
        } finally {
            context.close();
        }
    }

    private static TestService service(String serviceName) {
        return new TestService(serviceName);
    }

    /** Counts how often the registry asks the bean to describe itself, so memoization can be proven. */
    private static final class CountingService implements BindableService {

        private final TestService delegate;
        private int bindCount;

        private CountingService(String serviceName) {
            this.delegate = new TestService(serviceName);
        }

        @Override
        public ServerServiceDefinition bindService() {
            bindCount++;
            return delegate.bindService();
        }
    }

    /** A hand-written {@code BindableService}, so the fixture needs no protobuf code generation. */
    private static final class TestService implements BindableService {

        private final String serviceName;

        private TestService(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(serviceName)
                    .addMethod(method(MethodDescriptor.MethodType.UNARY, "Get"), handler())
                    .addMethod(method(MethodDescriptor.MethodType.SERVER_STREAMING, "Watch"), handler())
                    .build();
        }

        private MethodDescriptor<String, String> method(MethodDescriptor.MethodType type, String name) {
            return MethodDescriptor.<String, String>newBuilder()
                    .setType(type)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, name))
                    .setRequestMarshaller(MARSHALLER)
                    .setResponseMarshaller(MARSHALLER)
                    .build();
        }

        private static <T> ServerCallHandler<T, T> handler() {
            return (call, headers) -> {
                throw new UnsupportedOperationException("The panel never invokes a gRPC method");
            };
        }
    }

    private static final class BrokenService implements BindableService {

        @Override
        public ServerServiceDefinition bindService() {
            throw new IllegalStateException("cannot bind");
        }
    }

    private static final MethodDescriptor.Marshaller<String> MARSHALLER = new MethodDescriptor.Marshaller<>() {

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            throw new UnsupportedOperationException("The panel never reads a gRPC payload");
        }
    };

    @Test
    void neverExposesTheListOfDeclaredMethodsAsMutableState() {
        List<GrpcServiceSnapshot> services = registry(
                        Map.of("spring.grpc.server.port", "9090"),
                        Map.of("inventoryService", service("shop.Inventory")))
                .servers()
                .get(0)
                .services();

        assertThat(services.get(0).methods()).isUnmodifiable();
    }
}
