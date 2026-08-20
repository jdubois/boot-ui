package io.github.jdubois.bootui.quarkus.grpc;

import io.github.jdubois.bootui.spi.GrpcChannelSnapshot;
import io.github.jdubois.bootui.spi.GrpcMetadataProvider;
import io.github.jdubois.bootui.spi.GrpcMethodSnapshot;
import io.github.jdubois.bootui.spi.GrpcMethodType;
import io.github.jdubois.bootui.spi.GrpcRegistrySnapshot;
import io.github.jdubois.bootui.spi.GrpcServerSnapshot;
import io.github.jdubois.bootui.spi.GrpcServiceSnapshot;
import io.github.jdubois.bootui.spi.GrpcSetting;
import io.github.jdubois.bootui.spi.GrpcTransportSecurity;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.microprofile.config.Config;

/**
 * Reads the Quarkus gRPC registry for the gRPC panel: the {@code @GrpcService} CDI beans the application
 * registers, the {@code quarkus.grpc.server.*} transport configuration, and the
 * {@code quarkus.grpc.clients.*} managed channels.
 *
 * <p>This class is the only place in the Quarkus adapter that touches {@code io.grpc}. Its producer carries no
 * CDI scope and the deployment processor excludes it from bean discovery unless the {@code GRPC} capability is
 * present, so an application without {@code quarkus-grpc} never links an {@code io.grpc} type (R2).</p>
 *
 * <p>Strictly read-only. Services are described through {@link BindableService#bindService()}, which builds a
 * local {@link ServerServiceDefinition} from the generated stub's descriptors — no socket, no channel, no name
 * resolution, no RPC. Channels are described from configuration only, so listing them never creates a Vert.x
 * or Netty channel. TLS key material and keystore passwords are never read: only the presence of a TLS
 * configuration is reported.</p>
 */
public class QuarkusGrpcMetadataProvider implements GrpcMetadataProvider {

    static final String INTEGRATION = "Quarkus gRPC";

    private static final String SERVER_PREFIX = "quarkus.grpc.server.";
    private static final String CLIENTS_PREFIX = "quarkus.grpc.clients.";

    /**
     * Keys that identify a real client. One of them must resolve before an environment-variable spelling is
     * accepted as a channel name.
     */
    private static final List<String> CLIENT_IDENTITY_KEYS =
            List.of("host", "port", "name-resolver", "plain-text", "load-balancing-policy", "tls-configuration-name");

    private final Instance<BindableService> services;
    private final Config config;

    private volatile DescribedServices describedServices;

    public QuarkusGrpcMetadataProvider(Instance<BindableService> services, Config config) {
        this.services = services;
        this.config = config;
    }

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
        return INTEGRATION;
    }

    @Override
    public GrpcRegistrySnapshot registry() {
        List<String> warnings = new ArrayList<>();
        return new GrpcRegistrySnapshot(servers(warnings), channels(), warnings);
    }

    private List<GrpcServerSnapshot> servers(List<String> warnings) {
        List<GrpcServiceSnapshot> serviceSnapshots = services(warnings);
        if (serviceSnapshots.isEmpty() && !hasPropertyWithPrefix(SERVER_PREFIX)) {
            return List.of();
        }
        return List.of(new GrpcServerSnapshot(
                "quarkus-grpc-server",
                "gRPC server",
                address(),
                integer(SERVER_PREFIX + "port").orElse(null),
                serverSecurity(),
                reflectionEnabled(),
                longValue(SERVER_PREFIX + "max-inbound-message-size").orElse(null),
                longValue(SERVER_PREFIX + "max-inbound-metadata-size").orElse(null),
                serverKeepAlive(),
                serverSettings(),
                List.of(),
                serviceSnapshots));
    }

    private List<GrpcServiceSnapshot> services(List<String> warnings) {
        DescribedServices described = describedServices;
        if (described == null) {
            described = describeServices();
            describedServices = described;
        }
        warnings.addAll(described.warnings());
        return described.services();
    }

    /**
     * Describes every {@code BindableService} bean exactly once. {@link BindableService#bindService()} is
     * application code, and although the generated stubs only assemble static descriptors, the panel must not
     * re-run it on every page load or auto-refresh. The bean set and the descriptors it yields are fixed for
     * the lifetime of the container, so the first read is memoized and every later read is pure data.
     */
    private DescribedServices describeServices() {
        List<String> warnings = new ArrayList<>();
        List<GrpcServiceSnapshot> snapshots = new ArrayList<>();
        if (services == null || services.isUnsatisfied()) {
            return new DescribedServices(List.of(), List.of());
        }
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (BindableService bindableService : services) {
                ServerServiceDefinition definition;
                try {
                    definition = bindableService.bindService();
                } catch (RuntimeException ex) {
                    warnings.add("Could not describe gRPC service bean "
                            + bindableService.getClass().getName() + ": "
                            + ex.getClass().getSimpleName());
                    continue;
                }
                if (definition == null || definition.getServiceDescriptor() == null) {
                    continue;
                }
                String name = definition.getServiceDescriptor().getName();
                if (name == null || !seen.add(name)) {
                    continue;
                }
                List<GrpcMethodSnapshot> methods = new ArrayList<>();
                for (var method : definition.getMethods()) {
                    MethodDescriptor<?, ?> descriptor = method.getMethodDescriptor();
                    if (descriptor == null) {
                        continue;
                    }
                    String fullName = descriptor.getFullMethodName();
                    methods.add(new GrpcMethodSnapshot(
                            MethodDescriptor.extractBareMethodName(fullName), fullName, methodType(descriptor)));
                }
                snapshots.add(new GrpcServiceSnapshot(name, userClassName(bindableService), List.of(), methods));
            }
        } catch (RuntimeException ex) {
            // Resolving a bean can fail outside bindService(); report what was collected instead of failing.
            warnings.add(
                    "Could not enumerate gRPC service beans: " + ex.getClass().getSimpleName());
        }
        return new DescribedServices(List.copyOf(snapshots), List.copyOf(warnings));
    }

    /** Strips the Arc client-proxy suffix so the panel shows the application class, not a generated subclass. */
    private static String userClassName(BindableService bindableService) {
        String name = bindableService.getClass().getName();
        int proxy = name.indexOf("_ClientProxy");
        if (proxy > 0) {
            name = name.substring(0, proxy);
        }
        int subclass = name.indexOf("_Subclass");
        return subclass > 0 ? name.substring(0, subclass) : name;
    }

    private record DescribedServices(List<GrpcServiceSnapshot> services, List<String> warnings) {}

    /**
     * Quarkus force-enables the reflection service in dev mode regardless of
     * {@code quarkus.grpc.server.enable-reflection-service} ({@code GrpcServerRecorder}), so echoing the
     * property alone would tell a developer reflection is off while it is answering requests. The effective
     * value is reported instead. BootUI never enables reflection itself.
     */
    private Boolean reflectionEnabled() {
        if (bool(SERVER_PREFIX + "enable-reflection-service").orElse(false)) {
            return Boolean.TRUE;
        }
        return LaunchMode.current() == LaunchMode.DEVELOPMENT
                ? Boolean.TRUE
                : bool(SERVER_PREFIX + "enable-reflection-service").orElse(null);
    }

    private List<GrpcChannelSnapshot> channels() {
        List<GrpcChannelSnapshot> channels = new ArrayList<>();
        for (Map.Entry<String, String> client : channelPrefixes().entrySet()) {
            String prefix = client.getValue();
            channels.add(new GrpcChannelSnapshot(
                    client.getKey(),
                    target(prefix),
                    string(prefix + "load-balancing-policy").orElse(null),
                    channelSecurity(prefix),
                    bool(prefix + "retry").orElse(null),
                    longValue(prefix + "max-inbound-message-size").orElse(null),
                    longValue(prefix + "max-inbound-metadata-size").orElse(null),
                    channelKeepAlive(prefix),
                    channelSettings(prefix),
                    List.of()));
        }
        return channels;
    }

    /**
     * Quarkus describes a client by host and port rather than a single target string, so BootUI composes the
     * equivalent target for display. The name resolver is prefixed when one is configured, which is what makes
     * a Stork or DNS client legible without resolving anything.
     */
    private String target(String prefix) {
        String host = string(prefix + "host").orElse(null);
        Optional<Integer> port = integer(prefix + "port");
        if (host == null || host.isBlank()) {
            return null;
        }
        String authority = port.map(value -> host + ":" + value).orElse(host);
        String resolver = string(prefix + "name-resolver").orElse(null);
        return resolver == null || resolver.isBlank() ? authority : resolver + ":///" + authority;
    }

    /**
     * Client names are discovered by scanning the configuration for the {@code quarkus.grpc.clients.<name>.}
     * prefix. A name containing a dot is quoted in the key, so both spellings are unwrapped and de-duplicated
     * here, and each name keeps the prefix spelling it was actually configured with so subsequent lookups hit
     * the same keys the application declared.
     *
     * <p>A client configured purely through environment variables is exposed by the config source under its
     * raw {@code QUARKUS_GRPC_CLIENTS_<NAME>_<KEY>} spelling, which no literal prefix match can recognise.
     * Those names are recovered by translating the variable back to its dotted form, and are only accepted
     * when at least one client key actually resolves under the translated prefix, so a coincidental variable
     * name cannot invent a channel that does not exist.</p>
     */
    private Map<String, String> channelPrefixes() {
        Map<String, String> prefixes = new TreeMap<>();
        List<String> candidates = new ArrayList<>();
        for (String property : config.getPropertyNames()) {
            if (property == null) {
                continue;
            }
            if (property.startsWith(CLIENTS_PREFIX)) {
                addChannelPrefix(prefixes, property.substring(CLIENTS_PREFIX.length()));
                continue;
            }
            String dotted = property.toLowerCase(Locale.ROOT).replace('_', '.');
            if (dotted.startsWith(CLIENTS_PREFIX)) {
                candidates.add(dotted.substring(CLIENTS_PREFIX.length()));
            }
        }
        for (String candidate : candidates) {
            int dot = candidate.indexOf('.');
            String name = dot > 0 ? candidate.substring(0, dot) : candidate;
            if (name.isBlank() || prefixes.containsKey(name) || !hasClientKey(CLIENTS_PREFIX + name + ".")) {
                continue;
            }
            prefixes.put(name, CLIENTS_PREFIX + name + ".");
        }
        return prefixes;
    }

    private void addChannelPrefix(Map<String, String> prefixes, String remainder) {
        if (remainder.startsWith("\"")) {
            int closing = remainder.indexOf('"', 1);
            if (closing <= 1) {
                return;
            }
            String name = remainder.substring(1, closing);
            prefixes.putIfAbsent(name, CLIENTS_PREFIX + "\"" + name + "\".");
            return;
        }
        int dot = remainder.indexOf('.');
        String name = dot > 0 ? remainder.substring(0, dot) : remainder;
        if (!name.isBlank()) {
            prefixes.putIfAbsent(name, CLIENTS_PREFIX + name + ".");
        }
    }

    private boolean hasClientKey(String prefix) {
        for (String key : CLIENT_IDENTITY_KEYS) {
            if (string(prefix + key).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private String address() {
        if (bool(SERVER_PREFIX + "in-process.enabled").orElse(false)) {
            return "in-process:" + string(SERVER_PREFIX + "in-process.name").orElse("quarkus-grpc");
        }
        return string(SERVER_PREFIX + "host").orElse("0.0.0.0");
    }

    /**
     * Quarkus decides server transport security from the configured TLS <em>material</em>, mirroring
     * {@code GrpcServerConfiguration.isPlainTextEnabled()}: the transport is plaintext when {@code plain-text}
     * is enabled, or when neither a certificate nor a key store is configured. Only the presence of a value is
     * read; certificate paths, key paths, and keystore passwords are never pulled into the report.
     *
     * <p>Key <em>presence</em> is deliberately not used here. Quarkus declares {@code ssl.protocols} and
     * {@code ssl.client-auth} with defaults, so a prefix scan matches in every application and would report
     * TLS for every plaintext dev server — wrong in the unsafe direction on the most security-relevant field
     * of the panel.</p>
     */
    private GrpcTransportSecurity serverSecurity() {
        if (bool(SERVER_PREFIX + "plain-text").orElse(false)) {
            return GrpcTransportSecurity.PLAINTEXT;
        }
        boolean tlsConfigured = hasValue(SERVER_PREFIX + "tls-configuration-name")
                || hasValue(SERVER_PREFIX + "ssl.certificate")
                || hasValue(SERVER_PREFIX + "ssl.key-store")
                || hasValue(SERVER_PREFIX + "tls.key-certificate-pem.certs")
                || hasValue(SERVER_PREFIX + "tls.key-certificate-jks.path")
                || hasValue(SERVER_PREFIX + "tls.key-certificate-p12.path");
        return tlsConfigured ? GrpcTransportSecurity.TLS : GrpcTransportSecurity.PLAINTEXT;
    }

    /**
     * A Quarkus client is plaintext unless it is given TLS material, which is what
     * {@code Channels#createChannel} decides from {@code ssl.trust-store} with an explicit {@code plain-text}
     * override. {@code negotiation-type} is deliberately not consulted: it defaults to {@code TLS} and only
     * applies to the non-Quarkus Netty client path, so reading it would report TLS for every channel.
     */
    private GrpcTransportSecurity channelSecurity(String prefix) {
        Optional<Boolean> plainText = bool(prefix + "plain-text");
        if (plainText.isPresent()) {
            return plainText.get() ? GrpcTransportSecurity.PLAINTEXT : GrpcTransportSecurity.TLS;
        }
        boolean tlsConfigured = hasValue(prefix + "tls-configuration-name")
                || hasValue(prefix + "ssl.trust-store")
                || hasValue(prefix + "ssl.certificate")
                || hasValue(prefix + "ssl.key-store")
                || hasValue(prefix + "tls.trust-certificate-pem.certs")
                || hasValue(prefix + "tls.trust-certificate-jks.path")
                || hasValue(prefix + "tls.trust-certificate-p12.path");
        return tlsConfigured ? GrpcTransportSecurity.TLS : GrpcTransportSecurity.PLAINTEXT;
    }

    private boolean hasValue(String key) {
        return string(key).filter(value -> !value.isBlank()).isPresent();
    }

    private List<GrpcSetting> serverKeepAlive() {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Time", SERVER_PREFIX + "netty.keep-alive-time");
        addSetting(settings, "Permit time", SERVER_PREFIX + "netty.permit-keep-alive-time");
        addSetting(settings, "Permit without calls", SERVER_PREFIX + "netty.permit-keep-alive-without-calls");
        return settings;
    }

    private List<GrpcSetting> serverSettings() {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Separate server", SERVER_PREFIX + "use-separate-server");
        addSetting(settings, "Instances", SERVER_PREFIX + "instances");
        addSetting(settings, "Compression", SERVER_PREFIX + "compression");
        addSetting(settings, "Handshake timeout", SERVER_PREFIX + "handshake-timeout");
        addSetting(settings, "ALPN", SERVER_PREFIX + "alpn");
        addSetting(settings, "Health service", SERVER_PREFIX + "health.enabled");
        addSetting(settings, "In-process name", SERVER_PREFIX + "in-process.name");
        addSetting(settings, "TLS configuration", SERVER_PREFIX + "tls-configuration-name");
        return settings;
    }

    private List<GrpcSetting> channelKeepAlive(String prefix) {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Time", prefix + "keep-alive-time");
        addSetting(settings, "Timeout", prefix + "keep-alive-timeout");
        addSetting(settings, "Without calls", prefix + "keep-alive-without-calls");
        return settings;
    }

    private List<GrpcSetting> channelSettings(String prefix) {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Name resolver", prefix + "name-resolver");
        addSetting(settings, "Override authority", prefix + "override-authority");
        addSetting(settings, "Max retry attempts", prefix + "max-retry-attempts");
        addSetting(settings, "Deadline", prefix + "deadline");
        addSetting(settings, "Idle timeout", prefix + "idle-timeout");
        addSetting(settings, "Compression", prefix + "compression");
        addSetting(settings, "User agent", prefix + "user-agent");
        addSetting(settings, "Quarkus gRPC client", prefix + "use-quarkus-grpc-client");
        addSetting(settings, "TLS configuration", prefix + "tls-configuration-name");
        return settings;
    }

    private void addSetting(List<GrpcSetting> settings, String label, String key) {
        string(key).filter(value -> !value.isBlank()).ifPresent(value -> settings.add(new GrpcSetting(label, value)));
    }

    private Optional<String> string(String key) {
        try {
            return config.getOptionalValue(key, String.class);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> bool(String key) {
        try {
            return config.getOptionalValue(key, Boolean.class);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Integer> integer(String key) {
        try {
            return config.getOptionalValue(key, Integer.class);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Long> longValue(String key) {
        try {
            return config.getOptionalValue(key, Long.class);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean hasPropertyWithPrefix(String prefix) {
        for (String property : config.getPropertyNames()) {
            if (property == null) {
                continue;
            }
            // An environment-variable spelling of the same key is reported raw, so compare the dotted form too.
            if (property.startsWith(prefix)
                    || property.toLowerCase(Locale.ROOT).replace('_', '.').startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static GrpcMethodType methodType(MethodDescriptor<?, ?> descriptor) {
        MethodDescriptor.MethodType type = descriptor.getType();
        if (type == null) {
            return GrpcMethodType.UNKNOWN;
        }
        return switch (type) {
            case UNARY -> GrpcMethodType.UNARY;
            case CLIENT_STREAMING -> GrpcMethodType.CLIENT_STREAMING;
            case SERVER_STREAMING -> GrpcMethodType.SERVER_STREAMING;
            case BIDI_STREAMING -> GrpcMethodType.BIDI_STREAMING;
            case UNKNOWN -> GrpcMethodType.UNKNOWN;
        };
    }
}
