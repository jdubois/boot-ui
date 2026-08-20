package io.github.jdubois.bootui.engine.grpc;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.GrpcChannelDto;
import io.github.jdubois.bootui.core.dto.GrpcMethodDto;
import io.github.jdubois.bootui.core.dto.GrpcReport;
import io.github.jdubois.bootui.core.dto.GrpcServerDto;
import io.github.jdubois.bootui.core.dto.GrpcServiceDto;
import io.github.jdubois.bootui.core.dto.GrpcSettingDto;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral logic behind the gRPC panel, shared by the Spring Boot (servlet and WebFlux) and Quarkus
 * adapters.
 *
 * <p>It reads the host application's already-mapped gRPC registry from a {@link GrpcMetadataProvider} and
 * joins it with the aggregate call metrics the application already publishes, exposed through a
 * {@link GrpcMetricsProvider}. Everything here is read-only by construction: the service never creates a
 * channel or stub, resolves a name, issues an RPC, enables server reflection, or registers an interceptor, so
 * opening the panel cannot change the behaviour of the application it is describing.</p>
 *
 * <p>The engine owns the parts that must not drift between adapters: stable ordering, cardinality bounds with
 * honest truncation warnings, method-type and transport-security normalization, target redaction, exposure and
 * masking of setting values, and the "no matching metric series" decision. Adapters only map their framework's
 * registry and configuration into the neutral snapshot records.</p>
 */
public final class GrpcReportService {

    /** Bound on servers rendered in one report. */
    public static final int MAX_SERVERS = 20;
    /** Bound on services rendered per server. */
    public static final int MAX_SERVICES_PER_SERVER = 200;
    /** Bound on methods rendered per service. */
    public static final int MAX_METHODS_PER_SERVICE = 200;
    /** Bound on client channels rendered in one report. */
    public static final int MAX_CHANNELS = 50;
    /** Bound on observed client-side services rendered in one report. */
    public static final int MAX_CLIENT_SERVICES = 100;
    /** Bound on interceptor names rendered per row. */
    public static final int MAX_INTERCEPTORS = 25;
    /** Bound on settings rendered per row. */
    public static final int MAX_SETTINGS = 40;
    /** Bound on warnings rendered in one report. */
    public static final int MAX_WARNINGS = 25;

    static final String NO_INTEGRATION =
            "No supported gRPC integration was detected, so there is no gRPC registry to report.";
    static final String NO_METRICS =
            "No native gRPC metrics were found. BootUI reports only metrics the application already "
                    + "publishes and never installs its own gRPC interceptor to create them.";
    static final String UNATTRIBUTED_METRICS =
            "Native gRPC metrics were found but carried no service tag, so no call can be attributed to a "
                    + "service or method.";

    private final GrpcMetadataProvider metadataProvider;
    private final GrpcMetricsProvider metricsProvider;
    private final ExposurePolicy exposurePolicy;
    private final SecretMasker secretMasker;

    public GrpcReportService(
            GrpcMetadataProvider metadataProvider,
            GrpcMetricsProvider metricsProvider,
            ExposurePolicy exposurePolicy,
            SecretMasker secretMasker) {
        this.metadataProvider = metadataProvider;
        this.metricsProvider = metricsProvider == null ? GrpcMetricsProvider.UNAVAILABLE : metricsProvider;
        this.exposurePolicy = exposurePolicy;
        this.secretMasker = secretMasker == null ? new SecretMasker() : secretMasker;
    }

    /** The gRPC registry report; a stable empty report when no supported integration is present. */
    public GrpcReport report() {
        List<GrpcCallMetricSample> samples = nullSafe(metricsProvider.samples());
        GrpcCallAggregates aggregates = GrpcCallAggregates.of(samples);
        // Metrics count as available only when at least one sample could be attributed to a service. A
        // registry that publishes gRPC meters without a service tag would otherwise suppress the "no metrics"
        // explanation while every row still showed an em dash.
        boolean metricsAvailable = metricsProvider.available() && !aggregates.isEmpty();
        String metricsReason = metricsAvailable
                ? null
                : reasonOrDefault(
                        metricsProvider.available() && !samples.isEmpty() ? UNATTRIBUTED_METRICS : null,
                        reasonOrDefault(metricsProvider.unavailableReason(), NO_METRICS));
        if (metadataProvider == null || !metadataProvider.available()) {
            String reason = metadataProvider == null
                    ? NO_INTEGRATION
                    : reasonOrDefault(metadataProvider.unavailableReason(), NO_INTEGRATION);
            return GrpcReport.unavailable(reason, metricsReason);
        }

        GrpcRegistrySnapshot registry = metadataProvider.registry();
        if (registry == null) {
            registry = GrpcRegistrySnapshot.EMPTY;
        }
        List<String> warnings = new ArrayList<>();
        for (String warning : registry.warnings()) {
            addWarning(warnings, warning);
        }

        List<GrpcServerSnapshot> serverSnapshots = new ArrayList<>(registry.servers());
        serverSnapshots.sort(Comparator.comparing(
                snapshot -> displayName(snapshot.name(), snapshot.id()),
                Comparator.nullsLast(String::compareToIgnoreCase)));
        int serviceTotal = 0;
        int methodTotal = 0;
        for (GrpcServerSnapshot snapshot : serverSnapshots) {
            serviceTotal += snapshot.services().size();
            for (GrpcServiceSnapshot service : snapshot.services()) {
                methodTotal += service.methods().size();
            }
        }
        int serverTotal = serverSnapshots.size();
        if (serverTotal > MAX_SERVERS) {
            addWarning(warnings, "Showing the first " + MAX_SERVERS + " of " + serverTotal + " gRPC servers.");
            serverSnapshots = serverSnapshots.subList(0, MAX_SERVERS);
        }
        Set<String> usedIds = new LinkedHashSet<>();
        List<GrpcServerDto> servers = new ArrayList<>();
        for (GrpcServerSnapshot snapshot : serverSnapshots) {
            servers.add(toServer(snapshot, usedIds, aggregates, warnings));
        }

        List<GrpcChannelSnapshot> channelSnapshots = new ArrayList<>(registry.channels());
        channelSnapshots.sort(
                Comparator.comparing(GrpcChannelSnapshot::name, Comparator.nullsLast(String::compareToIgnoreCase)));
        int channelTotal = channelSnapshots.size();
        if (channelTotal > MAX_CHANNELS) {
            addWarning(warnings, "Showing the first " + MAX_CHANNELS + " of " + channelTotal + " client channels.");
            channelSnapshots = channelSnapshots.subList(0, MAX_CHANNELS);
        }
        List<GrpcChannelDto> channels =
                channelSnapshots.stream().map(this::toChannel).toList();

        List<GrpcServiceDto> clientServices = clientServices(aggregates, warnings);

        return new GrpcReport(
                true,
                null,
                metadataProvider.integration(),
                serverTotal,
                serviceTotal,
                methodTotal,
                channelTotal,
                metricsAvailable,
                metricsReason,
                servers,
                channels,
                clientServices,
                List.copyOf(warnings));
    }

    private GrpcServerDto toServer(
            GrpcServerSnapshot snapshot, Set<String> usedIds, GrpcCallAggregates aggregates, List<String> warnings) {
        List<GrpcServiceSnapshot> serviceSnapshots = new ArrayList<>(snapshot.services());
        serviceSnapshots.sort(
                Comparator.comparing(GrpcServiceSnapshot::name, Comparator.nullsLast(String::compareToIgnoreCase)));
        int serviceCount = serviceSnapshots.size();
        boolean servicesTruncated = serviceCount > MAX_SERVICES_PER_SERVER;
        if (servicesTruncated) {
            serviceSnapshots = serviceSnapshots.subList(0, MAX_SERVICES_PER_SERVER);
            addWarning(
                    warnings,
                    "Showing the first " + MAX_SERVICES_PER_SERVER + " of " + serviceCount + " services on "
                            + displayName(snapshot.name(), snapshot.id()) + ".");
        }
        int methodCount = 0;
        for (GrpcServiceSnapshot service : snapshot.services()) {
            methodCount += service.methods().size();
        }
        List<GrpcServiceDto> services = new ArrayList<>();
        for (GrpcServiceSnapshot service : serviceSnapshots) {
            services.add(toService(service, aggregates, warnings));
        }
        return new GrpcServerDto(
                uniqueId(snapshot.id(), snapshot.name(), usedIds),
                displayName(snapshot.name(), snapshot.id()),
                exposedTarget(snapshot.address()),
                snapshot.port(),
                security(snapshot.security()),
                snapshot.reflectionEnabled(),
                snapshot.maxInboundMessageSize(),
                snapshot.maxInboundMetadataSize(),
                settings(snapshot.keepAlive()),
                settings(snapshot.settings()),
                names(snapshot.interceptors()),
                serviceCount,
                methodCount,
                services,
                servicesTruncated);
    }

    private GrpcServiceDto toService(
            GrpcServiceSnapshot snapshot, GrpcCallAggregates aggregates, List<String> warnings) {
        List<GrpcMethodSnapshot> methodSnapshots = new ArrayList<>(snapshot.methods());
        methodSnapshots.sort(
                Comparator.comparing(method -> methodName(method), Comparator.nullsLast(String::compareToIgnoreCase)));
        int methodCount = methodSnapshots.size();
        boolean truncated = methodCount > MAX_METHODS_PER_SERVICE;
        if (truncated) {
            methodSnapshots = methodSnapshots.subList(0, MAX_METHODS_PER_SERVICE);
            addWarning(
                    warnings,
                    "Showing the first " + MAX_METHODS_PER_SERVICE + " of " + methodCount + " methods on "
                            + snapshot.name() + ".");
        }
        List<GrpcMethodDto> methods = methodSnapshots.stream()
                .map(method -> new GrpcMethodDto(
                        methodName(method),
                        method.fullName(),
                        methodType(method.type()),
                        aggregates.forMethod(GrpcCallSide.SERVER, snapshot.name(), methodName(method))))
                .toList();
        return new GrpcServiceDto(
                snapshot.name(),
                snapshot.implementationClass(),
                names(snapshot.interceptors()),
                methodCount,
                methods,
                truncated,
                aggregates.forService(GrpcCallSide.SERVER, snapshot.name()));
    }

    private GrpcChannelDto toChannel(GrpcChannelSnapshot snapshot) {
        String normalized = GrpcTargets.normalize(snapshot.target());
        return new GrpcChannelDto(
                snapshot.name(),
                exposed(normalized),
                exposed(GrpcTargets.authority(normalized)),
                snapshot.loadBalancingPolicy(),
                security(snapshot.security()),
                snapshot.retryEnabled(),
                snapshot.maxInboundMessageSize(),
                snapshot.maxInboundMetadataSize(),
                settings(snapshot.keepAlive()),
                settings(snapshot.settings()),
                names(snapshot.interceptors()));
    }

    /**
     * The remote services this application called, reconstructed only from client-side metric series. gRPC
     * client instrumentation is tagged by service and method, never by channel name, so BootUI reports these
     * as their own list instead of guessing which configured channel a call travelled over.
     */
    private List<GrpcServiceDto> clientServices(GrpcCallAggregates aggregates, List<String> warnings) {
        Map<String, Set<String>> observed = aggregates.observedServices(GrpcCallSide.CLIENT);
        if (observed.isEmpty()) {
            return List.of();
        }
        List<GrpcServiceDto> services = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : observed.entrySet()) {
            if (services.size() == MAX_CLIENT_SERVICES) {
                addWarning(
                        warnings,
                        "Showing the first " + MAX_CLIENT_SERVICES + " of " + observed.size()
                                + " services observed in client metrics.");
                break;
            }
            String service = entry.getKey();
            List<String> methodNames = new ArrayList<>(entry.getValue());
            boolean truncated = methodNames.size() > MAX_METHODS_PER_SERVICE;
            List<String> shown = truncated ? methodNames.subList(0, MAX_METHODS_PER_SERVICE) : methodNames;
            List<GrpcMethodDto> methods = shown.stream()
                    .map(method -> new GrpcMethodDto(
                            method,
                            service + "/" + method,
                            GrpcMethodType.UNKNOWN.name(),
                            aggregates.forMethod(GrpcCallSide.CLIENT, service, method)))
                    .toList();
            services.add(new GrpcServiceDto(
                    service,
                    null,
                    List.of(),
                    methodNames.size(),
                    methods,
                    truncated,
                    aggregates.forService(GrpcCallSide.CLIENT, service)));
        }
        return services;
    }

    private List<GrpcSettingDto> settings(List<GrpcSetting> settings) {
        List<GrpcSetting> source = nullSafe(settings);
        return source.stream()
                .filter(setting -> setting != null && setting.name() != null)
                .limit(MAX_SETTINGS)
                .map(setting -> new GrpcSettingDto(setting.name(), maskedValue(setting.name(), setting.value())))
                .toList();
    }

    /**
     * Interceptor names in encounter order. A chain's order is its semantics, so the adapter's order is
     * preserved rather than sorted; duplicates are dropped and the list is bounded.
     */
    private List<String> names(List<String> values) {
        return nullSafe(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(MAX_INTERCEPTORS)
                .toList();
    }

    private String maskedValue(String name, String value) {
        if (value == null) {
            return null;
        }
        ValueExposure exposure = exposurePolicy == null ? ValueExposure.MASKED : exposurePolicy.valueExposure();
        if (exposure == ValueExposure.FULL) {
            return value;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return SecretMasker.MASKED_VALUE;
        }
        boolean maskSecrets = exposurePolicy == null || exposurePolicy.maskSecrets();
        if (maskSecrets && secretMasker.shouldMask(name, value)) {
            return SecretMasker.MASKED_VALUE;
        }
        return value;
    }

    private String exposedTarget(String rawAddress) {
        return exposed(GrpcTargets.normalize(rawAddress));
    }

    /**
     * Applies the {@code METADATA_ONLY} exposure decision to an already-redacted address or target. Redaction
     * itself is unconditional and has already happened in {@link GrpcTargets}: an embedded credential is never
     * a value the user asked to see, even under {@code FULL}.
     */
    private String exposed(String redactedValue) {
        if (redactedValue == null) {
            return null;
        }
        ValueExposure exposure = exposurePolicy == null ? ValueExposure.MASKED : exposurePolicy.valueExposure();
        return exposure == ValueExposure.METADATA_ONLY ? SecretMasker.MASKED_VALUE : redactedValue;
    }

    private static String methodName(GrpcMethodSnapshot method) {
        if (method.name() != null && !method.name().isBlank()) {
            return method.name();
        }
        String fullName = method.fullName();
        if (fullName == null) {
            return null;
        }
        int slash = fullName.lastIndexOf('/');
        return slash >= 0 && slash + 1 < fullName.length() ? fullName.substring(slash + 1) : fullName;
    }

    private static String methodType(GrpcMethodType type) {
        return (type == null ? GrpcMethodType.UNKNOWN : type).name();
    }

    private static String security(GrpcTransportSecurity security) {
        return (security == null ? GrpcTransportSecurity.UNKNOWN : security).name();
    }

    private static String displayName(String name, String id) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id != null && !id.isBlank() ? id : "gRPC server";
    }

    private static String uniqueId(String id, String name, Set<String> usedIds) {
        String candidate = id != null && !id.isBlank() ? id : displayName(name, null);
        String unique = candidate;
        int suffix = 2;
        while (!usedIds.add(unique)) {
            unique = candidate + "-" + suffix++;
        }
        return unique;
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warning == null || warning.isBlank() || warnings.size() >= MAX_WARNINGS || warnings.contains(warning)) {
            return;
        }
        warnings.add(warning);
    }

    private static String reasonOrDefault(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
