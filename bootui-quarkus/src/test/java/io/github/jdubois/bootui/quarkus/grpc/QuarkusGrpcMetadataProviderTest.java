package io.github.jdubois.bootui.quarkus.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus.StubConfig;
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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the Quarkus gRPC registry is described from CDI beans and configuration only: no server is
 * started, no channel is created, and no name is resolved by any fixture here.
 */
class QuarkusGrpcMetadataProviderTest {

    private static final MethodDescriptor.Marshaller<String> MARSHALLER = new MethodDescriptor.Marshaller<>() {

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            throw new UnsupportedOperationException("The panel never reads a gRPC payload");
        }
    };

    @Test
    void isAvailableWheneverTheQuarkusGrpcExtensionIsPresent() {
        QuarkusGrpcMetadataProvider provider = provider(Map.of(), List.of());

        assertThat(provider.available()).isTrue();
        assertThat(provider.unavailableReason()).isNull();
        assertThat(provider.integration()).isEqualTo("Quarkus gRPC");
    }

    @Test
    void reportsNoServerWhenNoServiceOrServerConfigurationExists() {
        assertThat(provider(Map.of("quarkus.grpc.clients.hello.host", "localhost"), List.of())
                        .registry()
                        .servers())
                .isEmpty();
    }

    @Test
    void describesServerServicesAndMethodsFromGrpcServiceBeans() {
        GrpcRegistrySnapshot registry = provider(
                        Map.of("quarkus.grpc.server.port", "9000", "quarkus.grpc.server.host", "0.0.0.0"),
                        List.of(new TestService("shop.Inventory")))
                .registry();

        assertThat(registry.servers()).hasSize(1);
        GrpcServerSnapshot server = registry.servers().get(0);
        assertThat(server.id()).isEqualTo("quarkus-grpc-server");
        assertThat(server.address()).isEqualTo("0.0.0.0");
        assertThat(server.port()).isEqualTo(9000);
        assertThat(server.security()).isEqualTo(GrpcTransportSecurity.PLAINTEXT);
        GrpcServiceSnapshot service = server.services().get(0);
        assertThat(service.name()).isEqualTo("shop.Inventory");
        assertThat(service.implementationClass()).isEqualTo(TestService.class.getName());
        assertThat(service.methods())
                .extracting(GrpcMethodSnapshot::fullName)
                .containsExactlyInAnyOrder("shop.Inventory/Get", "shop.Inventory/Watch");
        assertThat(service.methods())
                .extracting(GrpcMethodSnapshot::type)
                .containsExactlyInAnyOrder(GrpcMethodType.UNARY, GrpcMethodType.SERVER_STREAMING);
    }

    @Test
    void readsServerTransportSettingsWithoutReadingTlsMaterial() {
        GrpcRegistrySnapshot registry = provider(
                        Map.of(
                                "quarkus.grpc.server.port", "9000",
                                "quarkus.grpc.server.plain-text", "false",
                                "quarkus.grpc.server.tls-configuration-name", "grpc-tls",
                                "quarkus.grpc.server.enable-reflection-service", "true",
                                "quarkus.grpc.server.max-inbound-message-size", "4194304",
                                "quarkus.grpc.server.max-inbound-metadata-size", "8192",
                                "quarkus.grpc.server.netty.keep-alive-time", "30S",
                                "quarkus.grpc.server.health.enabled", "true",
                                "quarkus.grpc.server.ssl.key-store-password", "s3cret"),
                        List.of())
                .registry();

        GrpcServerSnapshot server = registry.servers().get(0);
        assertThat(server.security()).isEqualTo(GrpcTransportSecurity.TLS);
        assertThat(server.reflectionEnabled()).isTrue();
        assertThat(server.maxInboundMessageSize()).isEqualTo(4194304L);
        assertThat(server.maxInboundMetadataSize()).isEqualTo(8192L);
        assertThat(server.keepAlive()).extracting(GrpcSetting::name).contains("Time");
        assertThat(server.settings()).extracting(GrpcSetting::value).doesNotContain("s3cret");
        assertThat(server.settings()).extracting(GrpcSetting::name).contains("TLS configuration", "Health service");
    }

    @Test
    void reportsAnInProcessServerAddressWhenTheInProcessTransportIsEnabled() {
        assertThat(provider(
                                Map.of(
                                        "quarkus.grpc.server.in-process.enabled", "true",
                                        "quarkus.grpc.server.in-process.name", "sample"),
                                List.of())
                        .registry()
                        .servers()
                        .get(0)
                        .address())
                .isEqualTo("in-process:sample");
    }

    @Test
    void composesEachClientTargetFromItsHostPortAndNameResolver() {
        GrpcRegistrySnapshot registry = provider(
                        Map.of(
                                "quarkus.grpc.clients.billing.host", "localhost",
                                "quarkus.grpc.clients.billing.port", "9001",
                                "quarkus.grpc.clients.billing.plain-text", "true",
                                "quarkus.grpc.clients.billing.load-balancing-policy", "round_robin",
                                "quarkus.grpc.clients.billing.max-inbound-message-size", "2097152",
                                "quarkus.grpc.clients.inventory.host", "inventory",
                                "quarkus.grpc.clients.inventory.name-resolver", "stork",
                                "quarkus.grpc.clients.inventory.ssl.trust-store", "truststore.p12",
                                "quarkus.grpc.clients.inventory.retry", "true"),
                        List.of())
                .registry();

        assertThat(registry.channels()).extracting(GrpcChannelSnapshot::name).containsExactly("billing", "inventory");
        GrpcChannelSnapshot billing = registry.channels().get(0);
        assertThat(billing.target()).isEqualTo("localhost:9001");
        assertThat(billing.security()).isEqualTo(GrpcTransportSecurity.PLAINTEXT);
        assertThat(billing.loadBalancingPolicy()).isEqualTo("round_robin");
        assertThat(billing.maxInboundMessageSize()).isEqualTo(2097152L);
        assertThat(billing.retryEnabled()).isNull();
        GrpcChannelSnapshot inventory = registry.channels().get(1);
        assertThat(inventory.target()).isEqualTo("stork:///inventory");
        assertThat(inventory.security()).isEqualTo(GrpcTransportSecurity.TLS);
        assertThat(inventory.retryEnabled()).isTrue();
    }

    @Test
    void readsQuotedClientNamesWithTheKeysTheyWereConfiguredWith() {
        GrpcRegistrySnapshot registry = provider(
                        Map.of(
                                "quarkus.grpc.clients.\"orders.eu\".host", "orders",
                                "quarkus.grpc.clients.\"orders.eu\".port", "9002"),
                        List.of())
                .registry();

        assertThat(registry.channels()).extracting(GrpcChannelSnapshot::name).containsExactly("orders.eu");
        assertThat(registry.channels().get(0).target()).isEqualTo("orders:9002");
    }

    @Test
    void reportsAClientWithoutTlsMaterialAsPlaintextBecauseThatIsWhatQuarkusBuilds() {
        // Quarkus decides client plaintext from the absence of TLS material, not from negotiation-type, whose
        // default value of TLS is never reached when no trust store or TLS configuration is present.
        assertThat(provider(
                                Map.of(
                                        "quarkus.grpc.clients.billing.host", "localhost",
                                        "quarkus.grpc.clients.billing.negotiation-type", "TLS"),
                                List.of())
                        .registry()
                        .channels()
                        .get(0)
                        .security())
                .isEqualTo(GrpcTransportSecurity.PLAINTEXT);
    }

    @Test
    void reportsAClientWithATlsConfigurationNameAsTls() {
        assertThat(provider(
                                Map.of(
                                        "quarkus.grpc.clients.billing.host", "localhost",
                                        "quarkus.grpc.clients.billing.tls-configuration-name", "grpc-client"),
                                List.of())
                        .registry()
                        .channels()
                        .get(0)
                        .security())
                .isEqualTo(GrpcTransportSecurity.TLS);
    }

    @Test
    void letsAnExplicitPlainTextOverrideWinOverTlsMaterial() {
        assertThat(provider(
                                Map.of(
                                        "quarkus.grpc.clients.billing.host", "localhost",
                                        "quarkus.grpc.clients.billing.ssl.trust-store", "truststore.p12",
                                        "quarkus.grpc.clients.billing.plain-text", "true"),
                                List.of())
                        .registry()
                        .channels()
                        .get(0)
                        .security())
                .isEqualTo(GrpcTransportSecurity.PLAINTEXT);
    }

    @Test
    void warnsInsteadOfFailingWhenAServiceBeanCannotBeDescribed() {
        GrpcRegistrySnapshot registry = provider(
                        Map.of("quarkus.grpc.server.port", "9000"),
                        List.of(new BrokenService(), new TestService("shop.Inventory")))
                .registry();

        assertThat(registry.warnings())
                .anySatisfy(warning -> assertThat(warning).contains(BrokenService.class.getName()));
        assertThat(registry.servers().get(0).services())
                .extracting(GrpcServiceSnapshot::name)
                .containsExactly("shop.Inventory");
    }

    @Test
    void toleratesAnAbsentServiceBeanContainer() {
        assertThat(new QuarkusGrpcMetadataProvider(new UnsatisfiedInstance<>(), StubConfig.empty())
                        .registry()
                        .servers())
                .isEmpty();
    }

    @Test
    void doesNotMistakeAnAlwaysPresentSslDefaultForAConfiguredServerCertificate() {
        // quarkus.grpc.server.ssl.protocols and ssl.client-auth carry framework defaults, so a key-presence
        // scan of the ssl. prefix would report every plaintext server as TLS.
        assertThat(provider(Map.of("quarkus.grpc.server.port", "9000"), List.of())
                        .registry()
                        .servers()
                        .get(0)
                        .security())
                .isEqualTo(GrpcTransportSecurity.PLAINTEXT);
    }

    @Test
    void reportsAServerWithACertificateAsTls() {
        assertThat(provider(
                                Map.of(
                                        "quarkus.grpc.server.port", "9000",
                                        "quarkus.grpc.server.ssl.certificate", "server.crt"),
                                List.of())
                        .registry()
                        .servers()
                        .get(0)
                        .security())
                .isEqualTo(GrpcTransportSecurity.TLS);
    }

    @Test
    void letsAnExplicitServerPlainTextOverrideWinOverACertificate() {
        assertThat(provider(
                                Map.of(
                                        "quarkus.grpc.server.port", "9000",
                                        "quarkus.grpc.server.ssl.certificate", "server.crt",
                                        "quarkus.grpc.server.plain-text", "true"),
                                List.of())
                        .registry()
                        .servers()
                        .get(0)
                        .security())
                .isEqualTo(GrpcTransportSecurity.PLAINTEXT);
    }

    @Test
    void recoversAClientNameSpelledAsAnEnvironmentVariable() {
        GrpcRegistrySnapshot registry = provider(
                        Map.of(
                                "QUARKUS_GRPC_CLIENTS_BILLING_HOST", "localhost",
                                "QUARKUS_GRPC_CLIENTS_BILLING_PORT", "9001"),
                        List.of())
                .registry();

        assertThat(registry.channels()).extracting(GrpcChannelSnapshot::name).containsExactly("billing");
    }

    @Test
    void describesEachServiceBeanOnlyOnceAcrossRepeatedReads() {
        CountingService counting = new CountingService("shop.Inventory");
        QuarkusGrpcMetadataProvider provider = provider(Map.of("quarkus.grpc.server.port", "9000"), List.of(counting));

        provider.registry();
        provider.registry();
        provider.registry();

        assertThat(counting.bindCount).isEqualTo(1);
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

    private static QuarkusGrpcMetadataProvider provider(
            Map<String, String> properties, List<BindableService> services) {
        return new QuarkusGrpcMetadataProvider(new ListInstance<>(services), new StubConfig(properties));
    }

    /** A hand-written {@code BindableService}, so the fixture needs no protobuf code generation. */
    private static final class TestService implements BindableService {

        private final String serviceName;

        private TestService(String serviceName) {
            this.serviceName = serviceName;
        }

        private static <T> ServerCallHandler<T, T> handler() {
            return (call, headers) -> {
                throw new UnsupportedOperationException("The panel never invokes a gRPC method");
            };
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
    }

    private static final class BrokenService implements BindableService {

        @Override
        public ServerServiceDefinition bindService() {
            throw new IllegalStateException("cannot bind");
        }
    }

    /**
     * A minimal {@link Instance} over a fixed list, standing in for the {@code @GrpcService} beans. This
     * module hand-rolls CDI {@link Instance} fakes rather than mocking them; see
     * {@code QuarkusDevServicesProviderTest} for the established practice.
     */
    private static class ListInstance<T> implements Instance<T> {

        private final List<T> values;

        ListInstance(List<T> values) {
            this.values = values;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return values.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return values.size() > 1;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return values.iterator();
        }

        @Override
        public T get() {
            return values.get(0);
        }
    }

    private static final class UnsatisfiedInstance<T> extends ListInstance<T> {

        UnsatisfiedInstance() {
            super(List.of());
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException("an unsatisfied Instance is never iterated");
        }
    }
}
