package io.github.jdubois.bootui.autoconfigure.grpc;

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
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.util.unit.DataSize;

/**
 * Reads the Spring Boot gRPC registry for the gRPC panel: the {@code io.grpc.BindableService} beans the
 * application registers, the {@code spring.grpc.server.*} transport configuration, and the
 * {@code spring.grpc.client.channel.*} managed channels.
 *
 * <p>This class is the only place in the Spring adapter that touches {@code io.grpc}, and it is loaded solely
 * from the {@code @ConditionalOnClass} nested configuration in {@code BootUiEngineConfiguration}, so an
 * application without gRPC never class-loads it (R2).</p>
 *
 * <p>Strictly read-only. Services are described by calling {@link BindableService#bindService()}, which builds
 * a local {@link ServerServiceDefinition} from the generated stub's descriptors — no socket, no channel, no
 * name resolution, no RPC. Channels are described from configuration only, so listing them never creates a
 * {@code ManagedChannel} and never opens a connection. Server reflection is read as a setting, never
 * enabled.</p>
 */
public class SpringGrpcMetadataProvider implements GrpcMetadataProvider {

    static final String INTEGRATION = "Spring Boot gRPC";

    private static final String SERVER_PREFIX = "spring.grpc.server.";
    private static final String CHANNEL_KEY = "spring.grpc.client.channel";
    private static final String CHANNEL_PREFIX = CHANNEL_KEY + ".";

    /** Service-name prefix of the gRPC server reflection service, in both its v1 and v1alpha spellings. */
    private static final String REFLECTION_SERVICE_PREFIX = "grpc.reflection.";

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final boolean present;
    private final String unavailableReason;

    private volatile DescribedServices describedServices;

    public SpringGrpcMetadataProvider(ApplicationContext applicationContext, Environment environment) {
        this(
                applicationContext,
                environment,
                SpringGrpcPresence.present(SpringGrpcMetadataProvider.class.getClassLoader()),
                SpringGrpcPresence.unavailableReason(SpringGrpcMetadataProvider.class.getClassLoader()));
    }

    /**
     * Test seam: classpath presence is resolved once at construction so a test can describe a gRPC
     * application without dragging Spring Boot's gRPC autoconfiguration (and a real transport) onto the test
     * classpath, where it would start an actual server.
     */
    SpringGrpcMetadataProvider(
            ApplicationContext applicationContext, Environment environment, boolean present, String unavailableReason) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.present = present;
        this.unavailableReason = unavailableReason;
    }

    @Override
    public boolean available() {
        return present;
    }

    @Override
    public String unavailableReason() {
        return present ? null : unavailableReason;
    }

    @Override
    public String integration() {
        return present ? INTEGRATION : null;
    }

    @Override
    public GrpcRegistrySnapshot registry() {
        if (!present) {
            return GrpcRegistrySnapshot.EMPTY;
        }
        List<String> warnings = new ArrayList<>();
        List<GrpcServerSnapshot> servers = servers(warnings);
        return new GrpcRegistrySnapshot(servers, channels(), warnings);
    }

    private List<GrpcServerSnapshot> servers(List<String> warnings) {
        List<GrpcServiceSnapshot> services = services(warnings);
        boolean serverConfigured = hasPropertyWithPrefix(SERVER_PREFIX);
        if (services.isEmpty() && !serverConfigured) {
            return List.of();
        }
        Integer port = property(SERVER_PREFIX + "port", Integer.class);
        return List.of(new GrpcServerSnapshot(
                "spring-grpc-server",
                "gRPC server",
                address(),
                port,
                serverSecurity(),
                reflectionEnabled(services),
                dataSize(SERVER_PREFIX + "inbound.message.max-size"),
                dataSize(SERVER_PREFIX + "inbound.metadata.max-size"),
                serverKeepAlive(),
                serverSettings(),
                interceptorNames(ServerInterceptor.class),
                services));
    }

    /**
     * Spring Boot enables the gRPC reflection service by default when {@code grpc-services} is on the
     * classpath ({@code matchIfMissing = true}), so echoing the raw property would report "unknown" for the
     * common case where reflection is actually answering. The registered reflection service is itself a
     * {@code BindableService}, which is a first-class signal: an explicit property wins, otherwise the
     * presence of the reflection service decides, and {@code null} is only reported when neither says
     * anything. BootUI never enables reflection itself.
     */
    private Boolean reflectionEnabled(List<GrpcServiceSnapshot> services) {
        Boolean configured = property(SERVER_PREFIX + "reflection.enabled", Boolean.class);
        if (configured != null) {
            return configured;
        }
        for (GrpcServiceSnapshot service : services) {
            if (service.name() != null && service.name().startsWith(REFLECTION_SERVICE_PREFIX)) {
                return Boolean.TRUE;
            }
        }
        return null;
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
     * the lifetime of the context, so the first read is memoized and every later read is pure data.
     */
    private DescribedServices describeServices() {
        List<String> warnings = new ArrayList<>();
        List<GrpcServiceSnapshot> services = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ObjectProvider<BindableService> beans = applicationContext.getBeanProvider(BindableService.class);
        List<BindableService> resolved;
        try {
            resolved = beans.orderedStream().toList();
        } catch (RuntimeException ex) {
            // Resolving a bean can fail outside bindService(); stay available and explain instead of failing.
            return new DescribedServices(
                    List.of(),
                    List.of("Could not enumerate gRPC service beans: "
                            + ex.getClass().getSimpleName()));
        }
        for (BindableService bindableService : resolved) {
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
            services.add(new GrpcServiceSnapshot(
                    name, ClassUtils.getUserClass(bindableService).getName(), List.of(), methods));
        }
        return new DescribedServices(List.copyOf(services), List.copyOf(warnings));
    }

    private record DescribedServices(List<GrpcServiceSnapshot> services, List<String> warnings) {}

    private List<GrpcChannelSnapshot> channels() {
        List<GrpcChannelSnapshot> channels = new ArrayList<>();
        for (String name : channelNames()) {
            String prefix = CHANNEL_PREFIX + name + ".";
            channels.add(new GrpcChannelSnapshot(
                    name,
                    environment.getProperty(prefix + "target"),
                    environment.getProperty(prefix + "default.load-balancing-policy"),
                    channelSecurity(prefix),
                    retryEnabled(prefix),
                    dataSize(prefix + "inbound.message.max-size"),
                    dataSize(prefix + "inbound.metadata.max-size"),
                    channelKeepAlive(prefix),
                    channelSettings(prefix),
                    List.of()));
        }
        return channels;
    }

    /**
     * Channel names are discovered through Spring Boot's own {@link Binder}, so relaxed binding decides what
     * counts as a channel name: {@code my-channel} in YAML and {@code SPRING_GRPC_CLIENT_CHANNEL_MYCHANNEL_*}
     * in the environment are both found, which a literal prefix scan of raw property names cannot do. The
     * scan is still applied afterwards because it is the only thing that recovers a quoted name containing
     * dots; the two sets are unioned and de-duplicated.
     */
    private Set<String> channelNames() {
        Set<String> names = new TreeSet<>();
        try {
            Binder.get(environment)
                    .bind(CHANNEL_KEY, Bindable.mapOf(String.class, Object.class))
                    .ifBound(bound -> bound.keySet().stream()
                            .filter(name -> name != null && !name.isBlank())
                            .forEach(names::add));
        } catch (RuntimeException ex) {
            // A binding failure must never take the panel down; the property scan below still applies.
        }
        ScannedChannels scanned = scannedChannelNames();
        // A quoted spelling is unambiguous evidence about where the channel name ends. When the binder split
        // "orders.eu" into a bare "orders", that leading segment is not a channel and must not be reported.
        names.removeIf(name -> scanned.quoted().stream().anyMatch(quoted -> quoted.startsWith(name + ".")));
        names.addAll(scanned.names());
        return names;
    }

    private ScannedChannels scannedChannelNames() {
        Set<String> names = new TreeSet<>();
        Set<String> quoted = new TreeSet<>();
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return new ScannedChannels(names, quoted);
        }
        for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String property : enumerable.getPropertyNames()) {
                String name = channelName(property);
                if (name == null) {
                    continue;
                }
                names.add(name);
                if (isQuotedChannelProperty(property)) {
                    quoted.add(name);
                }
            }
        }
        return new ScannedChannels(names, quoted);
    }

    private static boolean isQuotedChannelProperty(String property) {
        return property.startsWith(CHANNEL_PREFIX + "\"");
    }

    private record ScannedChannels(Set<String> names, Set<String> quoted) {}

    private static String channelName(String property) {
        if (property == null || !property.startsWith(CHANNEL_PREFIX)) {
            return null;
        }
        String remainder = property.substring(CHANNEL_PREFIX.length());
        if (remainder.startsWith("\"")) {
            int closing = remainder.indexOf('"', 1);
            return closing > 1 ? remainder.substring(1, closing) : null;
        }
        int dot = remainder.indexOf('.');
        String name = dot > 0 ? remainder.substring(0, dot) : remainder;
        return name.isBlank() ? null : name;
    }

    private String address() {
        String domainSocket = environment.getProperty(SERVER_PREFIX + "netty.domain-socket-path");
        if (domainSocket != null && !domainSocket.isBlank()) {
            return "unix:" + domainSocket;
        }
        String inProcess = environment.getProperty(SERVER_PREFIX + "inprocess.name");
        if (inProcess != null && !inProcess.isBlank()) {
            return "in-process:" + inProcess;
        }
        String configured = environment.getProperty(SERVER_PREFIX + "address");
        return configured == null || configured.isBlank() ? "*" : configured;
    }

    /**
     * A Spring Boot gRPC server serves TLS when an SSL bundle is configured, and an explicit
     * {@code ssl.enabled=false} overrides a bundle that is present but switched off. When the server rides the
     * servlet container ({@code spring.grpc.server.servlet.enabled}) the transport is decided by
     * {@code server.ssl.*} instead, which is not this server's configuration, so the honest answer is
     * {@code UNKNOWN} rather than a confident plaintext.
     */
    private GrpcTransportSecurity serverSecurity() {
        String bundle = environment.getProperty(SERVER_PREFIX + "ssl.bundle");
        Boolean enabled = property(SERVER_PREFIX + "ssl.enabled", Boolean.class);
        if (Boolean.FALSE.equals(enabled)) {
            return GrpcTransportSecurity.PLAINTEXT;
        }
        if (Boolean.TRUE.equals(enabled) || (bundle != null && !bundle.isBlank())) {
            return GrpcTransportSecurity.TLS;
        }
        return Boolean.TRUE.equals(property(SERVER_PREFIX + "servlet.enabled", Boolean.class))
                ? GrpcTransportSecurity.UNKNOWN
                : GrpcTransportSecurity.PLAINTEXT;
    }

    /**
     * A channel with neither {@code ssl.enabled} nor a bundle negotiates from its target scheme at connection
     * time, which BootUI does not simulate, so the honest answer there is {@code UNKNOWN}. An explicit
     * {@code ssl.enabled=false} wins over a configured bundle, matching Spring Boot's own binding.
     */
    private GrpcTransportSecurity channelSecurity(String prefix) {
        String bundle = environment.getProperty(prefix + "ssl.bundle");
        Boolean enabled = property(prefix + "ssl.enabled", Boolean.class);
        if (Boolean.FALSE.equals(enabled)) {
            return GrpcTransportSecurity.PLAINTEXT;
        }
        if (Boolean.TRUE.equals(enabled) || (bundle != null && !bundle.isBlank())) {
            return GrpcTransportSecurity.TLS;
        }
        return GrpcTransportSecurity.UNKNOWN;
    }

    /**
     * Retry is configured through the channel's service config rather than a dedicated flag. Spring Boot nests
     * the retry policy under {@code service-config.method[n].retry-policy} and additionally understands
     * {@code service-config.retry-throttling}, so any retry key beneath {@code service-config} counts and
     * {@code null} is reported when nothing says either way.
     */
    private Boolean retryEnabled(String prefix) {
        return hasPropertyMatching(prefix + "service-config.", "retry") ? Boolean.TRUE : null;
    }

    private List<GrpcSetting> serverKeepAlive() {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Time", SERVER_PREFIX + "keepalive.time");
        addSetting(settings, "Timeout", SERVER_PREFIX + "keepalive.timeout");
        addSetting(settings, "Max connection age", SERVER_PREFIX + "keepalive.connection.max-age");
        addSetting(settings, "Max connection idle time", SERVER_PREFIX + "keepalive.connection.max-idle-time");
        addSetting(settings, "Connection grace period", SERVER_PREFIX + "keepalive.connection.grace-period");
        addSetting(settings, "Permit time", SERVER_PREFIX + "keepalive.permit.time");
        addSetting(settings, "Permit without calls", SERVER_PREFIX + "keepalive.permit.without-calls");
        return settings;
    }

    private List<GrpcSetting> serverSettings() {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Enabled", SERVER_PREFIX + "enabled");
        addSetting(settings, "Servlet transport", SERVER_PREFIX + "servlet.enabled");
        addSetting(settings, "Netty transport", SERVER_PREFIX + "netty.transport");
        addSetting(settings, "In-process name", SERVER_PREFIX + "inprocess.name");
        addSetting(settings, "Observations", SERVER_PREFIX + "observation.enabled");
        addSetting(settings, "Health service", SERVER_PREFIX + "health.enabled");
        addSetting(settings, "Shutdown grace period", SERVER_PREFIX + "shutdown.grace-period");
        addSetting(settings, "SSL bundle", SERVER_PREFIX + "ssl.bundle");
        addSetting(settings, "Client authentication", SERVER_PREFIX + "ssl.client-auth");
        return settings;
    }

    private List<GrpcSetting> channelKeepAlive(String prefix) {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Time", prefix + "keepalive.time");
        addSetting(settings, "Timeout", prefix + "keepalive.timeout");
        addSetting(settings, "Without calls", prefix + "keepalive.without-calls");
        return settings;
    }

    private List<GrpcSetting> channelSettings(String prefix) {
        List<GrpcSetting> settings = new ArrayList<>();
        addSetting(settings, "Default deadline", prefix + "default.deadline");
        addSetting(settings, "Idle timeout", prefix + "idle.timeout");
        addSetting(settings, "User agent", prefix + "user-agent");
        addSetting(settings, "SSL bundle", prefix + "ssl.bundle");
        addSetting(settings, "Health check", prefix + "health.enabled");
        addSetting(settings, "Health service name", prefix + "health.service-name");
        return settings;
    }

    private void addSetting(List<GrpcSetting> settings, String label, String key) {
        String value = environment.getProperty(key);
        if (value != null && !value.isBlank()) {
            settings.add(new GrpcSetting(label, value));
        }
    }

    private List<String> interceptorNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (String beanName : applicationContext.getBeanNamesForType(type, true, false)) {
            Class<?> beanType = applicationContext.getType(beanName);
            names.add(beanType == null ? beanName : beanType.getName());
        }
        return names;
    }

    /**
     * Parsed directly rather than through the environment's conversion service, because a {@code DataSize}
     * converter is only registered on a full Spring Boot application context.
     */
    private Long dataSize(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DataSize.parse(value.trim()).toBytes();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private <T> T property(String key, Class<T> type) {
        try {
            return environment.getProperty(key, type);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean hasPropertyMatching(String prefix, String contains) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return false;
        }
        for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String property : enumerable.getPropertyNames()) {
                if (property != null && property.startsWith(prefix) && property.contains(contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPropertyWithPrefix(String prefix) {
        String key = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
        // Ask the binder first so an environment-variable spelling of the same key is recognised.
        if (environment.containsProperty(key)) {
            return true;
        }
        try {
            if (Binder.get(environment)
                    .bind(key, Bindable.mapOf(String.class, Object.class))
                    .isBound()) {
                return true;
            }
        } catch (RuntimeException ex) {
            // Fall through to the raw scan below.
        }
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return false;
        }
        for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String property : enumerable.getPropertyNames()) {
                if (property != null && property.startsWith(prefix)) {
                    return true;
                }
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
