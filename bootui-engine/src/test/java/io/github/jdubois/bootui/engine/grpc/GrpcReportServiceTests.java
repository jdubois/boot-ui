package io.github.jdubois.bootui.engine.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.GrpcChannelDto;
import io.github.jdubois.bootui.core.dto.GrpcReport;
import io.github.jdubois.bootui.core.dto.GrpcServerDto;
import io.github.jdubois.bootui.core.dto.GrpcServiceDto;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.GrpcCallMetricSample;
import io.github.jdubois.bootui.spi.GrpcCallSide;
import io.github.jdubois.bootui.spi.GrpcChannelSnapshot;
import io.github.jdubois.bootui.spi.GrpcMetadataProvider;
import io.github.jdubois.bootui.spi.GrpcMethodSnapshot;
import io.github.jdubois.bootui.spi.GrpcMethodType;
import io.github.jdubois.bootui.spi.GrpcMetricsProvider;
import io.github.jdubois.bootui.spi.GrpcRegistrySnapshot;
import io.github.jdubois.bootui.spi.GrpcServerSnapshot;
import io.github.jdubois.bootui.spi.GrpcServiceSnapshot;
import io.github.jdubois.bootui.spi.GrpcSetting;
import io.github.jdubois.bootui.spi.GrpcTransportSecurity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework-neutral {@link GrpcReportService}.
 *
 * <p>They pin the acceptance criteria of the gRPC panel that must not drift between the Spring MVC, Spring
 * WebFlux, and Quarkus adapters: the honest unavailable states (absent integration, absent metrics), the
 * unary/streaming method-type contract, multiple channels with plaintext/TLS/unknown transport and reflection
 * on/off, target redaction under each exposure policy, the metric join (including client-side series that
 * belong to no configured channel), and the cardinality bounds with their truncation warnings.
 */
class GrpcReportServiceTests {

    private static final ExposurePolicy MASKED = policy(ValueExposure.MASKED, true);

    private static ExposurePolicy policy(ValueExposure exposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }

    private static GrpcMetadataProvider provider(GrpcRegistrySnapshot registry) {
        return new GrpcMetadataProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public String integration() {
                return "Test gRPC";
            }

            @Override
            public GrpcRegistrySnapshot registry() {
                return registry;
            }
        };
    }

    private static GrpcMetadataProvider unavailableProvider(String reason) {
        return new GrpcMetadataProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String unavailableReason() {
                return reason;
            }

            @Override
            public String integration() {
                return null;
            }

            @Override
            public GrpcRegistrySnapshot registry() {
                return GrpcRegistrySnapshot.EMPTY;
            }
        };
    }

    private static GrpcMetricsProvider metrics(List<GrpcCallMetricSample> samples) {
        return new GrpcMetricsProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public List<GrpcCallMetricSample> samples() {
                return samples;
            }
        };
    }

    @Test
    void explainsWhenGrpcMetersExistButCarryNoServiceTag() {
        // A binder can publish gRPC meters without a service tag. Reporting "metrics available" then would leave
        // every row on an em dash with no explanation, so the report says why instead.
        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(
                                List.of(new GrpcServerSnapshot(
                                        "grpc-server",
                                        "gRPC server",
                                        "*",
                                        9090,
                                        GrpcTransportSecurity.PLAINTEXT,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(service(
                                                "example.Greeter",
                                                method("example.Greeter", "SayHello", GrpcMethodType.UNARY))))),
                                List.of(),
                                List.of())),
                        metrics(List.of(new GrpcCallMetricSample(
                                GrpcCallSide.SERVER, "  ", "SayHello", "OK", 7L, 70d, 20d, null))))
                .report();

        assertThat(report.available()).isTrue();
        assertThat(report.metricsAvailable()).isFalse();
        assertThat(report.metricsUnavailableReason()).isEqualTo(GrpcReportService.UNATTRIBUTED_METRICS);
        assertThat(report.clientServices()).isEmpty();
    }

    private static GrpcServiceSnapshot service(String name, GrpcMethodSnapshot... methods) {
        return new GrpcServiceSnapshot(name, name + "Impl", List.of(), List.of(methods));
    }

    private static GrpcMethodSnapshot method(String service, String name, GrpcMethodType type) {
        return new GrpcMethodSnapshot(name, service + "/" + name, type);
    }

    private static GrpcServerSnapshot server(String id, String name, GrpcServiceSnapshot... services) {
        return new GrpcServerSnapshot(
                id,
                name,
                "0.0.0.0",
                9090,
                GrpcTransportSecurity.PLAINTEXT,
                Boolean.TRUE,
                4194304L,
                8192L,
                List.of(new GrpcSetting("Time", "2h")),
                List.of(new GrpcSetting("Observations", "true")),
                List.of("com.example.LoggingInterceptor"),
                List.of(services));
    }

    private GrpcReportService service(GrpcMetadataProvider metadata, GrpcMetricsProvider metricsProvider) {
        return new GrpcReportService(metadata, metricsProvider, MASKED, new SecretMasker());
    }

    @Test
    void reportsUnavailableWithoutAGrpcIntegration() {
        GrpcReport report = service(null, GrpcMetricsProvider.UNAVAILABLE).report();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo(GrpcReportService.NO_INTEGRATION);
        assertThat(report.metricsAvailable()).isFalse();
        assertThat(report.servers()).isEmpty();
        assertThat(report.channels()).isEmpty();
        assertThat(report.clientServices()).isEmpty();
        assertThat(report.serverCount()).isZero();
    }

    @Test
    void keepsTheAdapterUnavailableReasonWhenTheProviderExplainsItself() {
        GrpcReport report = service(
                        unavailableProvider("Add the quarkus-grpc extension."), GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("Add the quarkus-grpc extension.");
    }

    @Test
    void reportsMetricsUnavailableRatherThanZerosWhenNoSeriesArePublished() {
        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(
                                List.of(server(
                                        "s1",
                                        "gRPC server",
                                        service(
                                                "example.Greeter",
                                                method("example.Greeter", "SayHello", GrpcMethodType.UNARY)))),
                                List.of(),
                                List.of())),
                        metrics(List.of()))
                .report();

        assertThat(report.available()).isTrue();
        assertThat(report.metricsAvailable()).isFalse();
        assertThat(report.metricsUnavailableReason()).isEqualTo(GrpcReportService.NO_METRICS);
        assertThat(report.servers()).hasSize(1);
        assertThat(report.servers()
                        .get(0)
                        .services()
                        .get(0)
                        .methods()
                        .get(0)
                        .metrics()
                        .available())
                .isFalse();
    }

    @Test
    void describesUnaryAndStreamingMethodsWithTransportAndReflectionState() {
        GrpcServerSnapshot plaintext = server(
                "plain",
                "Plaintext server",
                service(
                        "example.Greeter",
                        method("example.Greeter", "SayHello", GrpcMethodType.UNARY),
                        method("example.Greeter", "Chat", GrpcMethodType.BIDI_STREAMING),
                        method("example.Greeter", "Download", GrpcMethodType.SERVER_STREAMING),
                        method("example.Greeter", "Upload", GrpcMethodType.CLIENT_STREAMING)));
        GrpcServerSnapshot secure = new GrpcServerSnapshot(
                "secure",
                "TLS server",
                "10.0.0.1",
                9443,
                GrpcTransportSecurity.TLS,
                Boolean.FALSE,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(List.of(secure, plaintext), List.of(), List.of())),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.serverCount()).isEqualTo(2);
        assertThat(report.serviceCount()).isEqualTo(1);
        assertThat(report.methodCount()).isEqualTo(4);
        assertThat(report.servers()).extracting(GrpcServerDto::name).containsExactly("Plaintext server", "TLS server");

        GrpcServerDto first = report.servers().get(0);
        assertThat(first.transportSecurity()).isEqualTo("PLAINTEXT");
        assertThat(first.reflectionEnabled()).isTrue();
        assertThat(first.maxInboundMessageSize()).isEqualTo(4194304L);
        assertThat(first.services().get(0).methods())
                .extracting("name", "type")
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("Chat", "BIDI_STREAMING"),
                        org.assertj.core.api.Assertions.tuple("Download", "SERVER_STREAMING"),
                        org.assertj.core.api.Assertions.tuple("SayHello", "UNARY"),
                        org.assertj.core.api.Assertions.tuple("Upload", "CLIENT_STREAMING"));

        GrpcServerDto second = report.servers().get(1);
        assertThat(second.transportSecurity()).isEqualTo("TLS");
        assertThat(second.reflectionEnabled()).isFalse();
        assertThat(second.services()).isEmpty();
    }

    @Test
    void keepsMultipleChannelsDistinctAndOrderedWithTheirTransportSecurity() {
        GrpcChannelSnapshot plaintext = new GrpcChannelSnapshot(
                "inventory",
                "static://localhost:9090",
                "round_robin",
                GrpcTransportSecurity.PLAINTEXT,
                Boolean.TRUE,
                1024L,
                null,
                List.of(),
                List.of(),
                List.of());
        GrpcChannelSnapshot tls = new GrpcChannelSnapshot(
                "billing",
                "dns:///billing.internal:443",
                null,
                GrpcTransportSecurity.TLS,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
        GrpcChannelSnapshot unknown = new GrpcChannelSnapshot(
                "audit", "audit.internal:9090", null, null, null, null, null, List.of(), List.of(), List.of());

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(List.of(), List.of(plaintext, tls, unknown), List.of())),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.channelCount()).isEqualTo(3);
        assertThat(report.channels()).extracting(GrpcChannelDto::name).containsExactly("audit", "billing", "inventory");
        assertThat(report.channels())
                .extracting(GrpcChannelDto::transportSecurity)
                .containsExactly("UNKNOWN", "TLS", "PLAINTEXT");
        assertThat(report.channels().get(1).authority()).isEqualTo("billing.internal:443");
        assertThat(report.channels().get(2).loadBalancingPolicy()).isEqualTo("round_robin");
        assertThat(report.channels().get(2).retryEnabled()).isTrue();
    }

    @Test
    void redactsCredentialsInChannelTargetsEvenUnderFullExposure() {
        GrpcChannelSnapshot channel = new GrpcChannelSnapshot(
                "secrets",
                "dns://alice:hunter2@billing.internal:443/path?token=abcdef",
                null,
                GrpcTransportSecurity.TLS,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
        GrpcReportService full = new GrpcReportService(
                provider(new GrpcRegistrySnapshot(List.of(), List.of(channel), List.of())),
                GrpcMetricsProvider.UNAVAILABLE,
                policy(ValueExposure.FULL, false),
                new SecretMasker());

        GrpcChannelDto dto = full.report().channels().get(0);

        assertThat(dto.target()).doesNotContain("hunter2").doesNotContain("token=abcdef");
        assertThat(dto.target()).contains(SecretMasker.MASKED_VALUE);
        assertThat(dto.authority()).isEqualTo("billing.internal:443");
    }

    @Test
    void masksAddressesEntirelyUnderMetadataOnlyExposure() {
        GrpcChannelSnapshot channel = new GrpcChannelSnapshot(
                "billing",
                "dns:///billing.internal:443",
                null,
                GrpcTransportSecurity.TLS,
                null,
                null,
                null,
                List.of(),
                List.of(new GrpcSetting("User agent", "bootui-sample")),
                List.of());
        GrpcReportService metadataOnly = new GrpcReportService(
                provider(new GrpcRegistrySnapshot(List.of(server("s1", "gRPC server")), List.of(channel), List.of())),
                GrpcMetricsProvider.UNAVAILABLE,
                policy(ValueExposure.METADATA_ONLY, true),
                new SecretMasker());

        GrpcReport report = metadataOnly.report();

        assertThat(report.channels().get(0).target()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(report.channels().get(0).authority()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(report.channels().get(0).settings().get(0).value()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(report.servers().get(0).address()).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void toleratesMalformedTargetsWithoutFailing() {
        GrpcChannelSnapshot malformed =
                new GrpcChannelSnapshot("broken", ":::", null, null, null, null, null, List.of(), List.of(), List.of());
        GrpcChannelSnapshot blank =
                new GrpcChannelSnapshot("blank", "   ", null, null, null, null, null, List.of(), List.of(), List.of());

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(List.of(), List.of(malformed, blank), List.of())),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.channels()).hasSize(2);
        assertThat(report.channels().get(0).target()).isNull();
        assertThat(report.channels().get(1).target()).isEqualTo(":::");
        assertThat(report.channels().get(1).authority()).isNull();
    }

    @Test
    void joinsServerMetricsPerServiceAndPerMethodWithStatusCounts() {
        GrpcServerSnapshot snapshot = server(
                "s1",
                "gRPC server",
                service(
                        "example.Greeter",
                        method("example.Greeter", "SayHello", GrpcMethodType.UNARY),
                        method("example.Greeter", "Chat", GrpcMethodType.BIDI_STREAMING)));
        List<GrpcCallMetricSample> samples = List.of(
                new GrpcCallMetricSample(GrpcCallSide.SERVER, "example.Greeter", "SayHello", "OK", 8L, 80d, 25d, null),
                new GrpcCallMetricSample(
                        GrpcCallSide.SERVER, "example.Greeter", "SayHello", "UNAVAILABLE", 2L, 40d, 30d, null),
                new GrpcCallMetricSample(GrpcCallSide.SERVER, "example.Greeter", "Chat", null, 0L, null, null, 3L));

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(List.of(snapshot), List.of(), List.of())), metrics(samples))
                .report();

        assertThat(report.metricsAvailable()).isTrue();
        assertThat(report.metricsUnavailableReason()).isNull();

        GrpcServiceDto greeter = report.servers().get(0).services().get(0);
        assertThat(greeter.metrics().available()).isTrue();
        assertThat(greeter.metrics().callCount()).isEqualTo(10L);
        assertThat(greeter.metrics().activeCalls()).isEqualTo(3L);
        assertThat(greeter.metrics().totalDurationMs()).isEqualTo(120d);
        assertThat(greeter.metrics().maxDurationMs()).isEqualTo(30d);
        assertThat(greeter.metrics().averageDurationMs()).isEqualTo(12d);
        assertThat(greeter.metrics().statusCounts())
                .extracting("status", "count")
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("OK", 8L),
                        org.assertj.core.api.Assertions.tuple("UNAVAILABLE", 2L));

        assertThat(greeter.methods().get(0).name()).isEqualTo("Chat");
        assertThat(greeter.methods().get(0).metrics().callCount()).isZero();
        assertThat(greeter.methods().get(0).metrics().activeCalls()).isEqualTo(3L);
        assertThat(greeter.methods().get(1).metrics().callCount()).isEqualTo(10L);
    }

    @Test
    void reconstructsOutgoingCallsFromClientMetricsWithoutAttributingThemToAChannel() {
        List<GrpcCallMetricSample> samples = List.of(
                new GrpcCallMetricSample(GrpcCallSide.CLIENT, "billing.Ledger", "Charge", "OK", 4L, 200d, 90d, null),
                new GrpcCallMetricSample(GrpcCallSide.CLIENT, "billing.Ledger", "Refund", "OK", 1L, 30d, 30d, null),
                new GrpcCallMetricSample(GrpcCallSide.CLIENT, "audit.Log", "Append", "OK", 2L, 10d, 6d, null),
                new GrpcCallMetricSample(GrpcCallSide.CLIENT, "  ", "Ignored", "OK", 99L, 1d, 1d, null));

        GrpcReport report =
                service(provider(GrpcRegistrySnapshot.EMPTY), metrics(samples)).report();

        assertThat(report.clientServices())
                .extracting(GrpcServiceDto::name)
                .containsExactly("audit.Log", "billing.Ledger");
        GrpcServiceDto ledger = report.clientServices().get(1);
        assertThat(ledger.implementationClass()).isNull();
        assertThat(ledger.methodCount()).isEqualTo(2);
        assertThat(ledger.metrics().callCount()).isEqualTo(5L);
        assertThat(ledger.methods()).extracting("name").containsExactly("Charge", "Refund");
        assertThat(ledger.methods().get(0).fullName()).isEqualTo("billing.Ledger/Charge");
        assertThat(ledger.methods().get(0).type()).isEqualTo("UNKNOWN");
        assertThat(report.channels()).isEmpty();
    }

    @Test
    void boundsHighCardinalityRegistriesAndExplainsTheTruncation() {
        List<GrpcServiceSnapshot> services = new ArrayList<>();
        for (int index = 0; index < GrpcReportService.MAX_SERVICES_PER_SERVER + 5; index++) {
            services.add(service(String.format("example.Service%03d", index)));
        }
        List<GrpcMethodSnapshot> methods = new ArrayList<>();
        for (int index = 0; index < GrpcReportService.MAX_METHODS_PER_SERVICE + 3; index++) {
            methods.add(method("example.Wide", String.format("Method%03d", index), GrpcMethodType.UNARY));
        }
        services.add(new GrpcServiceSnapshot("example.Wide", "WideImpl", List.of(), methods));

        List<GrpcChannelSnapshot> channels = new ArrayList<>();
        for (int index = 0; index < GrpcReportService.MAX_CHANNELS + 7; index++) {
            channels.add(new GrpcChannelSnapshot(
                    String.format("channel-%03d", index),
                    "static://localhost:9090",
                    null,
                    GrpcTransportSecurity.PLAINTEXT,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of()));
        }

        GrpcServerSnapshot snapshot = new GrpcServerSnapshot(
                "s1",
                "gRPC server",
                "0.0.0.0",
                9090,
                GrpcTransportSecurity.PLAINTEXT,
                Boolean.TRUE,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                services);

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(List.of(snapshot), channels, List.of())),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.serviceCount()).isEqualTo(services.size());
        assertThat(report.channelCount()).isEqualTo(channels.size());
        assertThat(report.servers().get(0).services()).hasSize(GrpcReportService.MAX_SERVICES_PER_SERVER);
        assertThat(report.servers().get(0).servicesTruncated()).isTrue();
        assertThat(report.channels()).hasSize(GrpcReportService.MAX_CHANNELS);
        assertThat(report.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("services"))
                .anySatisfy(warning -> assertThat(warning).contains("client channels"));
    }

    @Test
    void boundsMethodsPerServiceAndFlagsTheServiceAsTruncated() {
        List<GrpcMethodSnapshot> methods = new ArrayList<>();
        for (int index = 0; index < GrpcReportService.MAX_METHODS_PER_SERVICE + 4; index++) {
            methods.add(method("example.Wide", String.format("Method%03d", index), GrpcMethodType.UNARY));
        }
        GrpcServerSnapshot snapshot = new GrpcServerSnapshot(
                "s1",
                "gRPC server",
                "0.0.0.0",
                9090,
                GrpcTransportSecurity.PLAINTEXT,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new GrpcServiceSnapshot("example.Wide", "WideImpl", List.of(), methods)));

        GrpcServiceDto rendered = service(
                        provider(new GrpcRegistrySnapshot(List.of(snapshot), List.of(), List.of())),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report()
                .servers()
                .get(0)
                .services()
                .get(0);

        assertThat(rendered.methodCount()).isEqualTo(methods.size());
        assertThat(rendered.methods()).hasSize(GrpcReportService.MAX_METHODS_PER_SERVICE);
        assertThat(rendered.methodsTruncated()).isTrue();
    }

    @Test
    void keepsServerIdentitiesDistinctWhenAnAdapterRepeatsAnId() {
        GrpcServerSnapshot first = server("dup", "Alpha");
        GrpcServerSnapshot second = server("dup", "Beta");

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(List.of(first, second), List.of(), List.of())),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.servers()).extracting(GrpcServerDto::id).containsExactly("dup", "dup-2");
    }

    @Test
    void masksSecretLikeSettingValuesAndKeepsProviderWarnings() {
        GrpcServerSnapshot snapshot = new GrpcServerSnapshot(
                "s1",
                "gRPC server",
                "0.0.0.0",
                9090,
                GrpcTransportSecurity.PLAINTEXT,
                null,
                null,
                null,
                List.of(),
                List.of(new GrpcSetting("password", "hunter2"), new GrpcSetting("Observations", "true")),
                List.of("com.example.B", "com.example.A", "com.example.A"),
                List.of());

        GrpcReport report = service(
                        provider(new GrpcRegistrySnapshot(
                                List.of(snapshot), List.of(), List.of("Could not describe one service bean."))),
                        GrpcMetricsProvider.UNAVAILABLE)
                .report();

        assertThat(report.servers().get(0).settings())
                .extracting("name", "value")
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("password", SecretMasker.MASKED_VALUE),
                        org.assertj.core.api.Assertions.tuple("Observations", "true"));
        // Interceptor order is chain order, so it is preserved rather than sorted; duplicates are still dropped.
        assertThat(report.servers().get(0).interceptors()).containsExactly("com.example.B", "com.example.A");
        assertThat(report.warnings()).containsExactly("Could not describe one service bean.");
    }
}
