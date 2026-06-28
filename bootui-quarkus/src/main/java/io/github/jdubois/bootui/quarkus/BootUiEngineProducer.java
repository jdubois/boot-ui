package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.metrics.MeterSelfFilter;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.quarkus.health.QuarkusHealthGuidance;
import io.github.jdubois.bootui.quarkus.logging.QuarkusLoggerProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;
import org.eclipse.microprofile.config.Config;

/**
 * Produces the framework-neutral {@code bootui-engine} services as CDI singletons for the Quarkus
 * adapter.
 *
 * <p>This is the Quarkus analogue of the Spring adapter's {@code BootUiEngineConfiguration}: the engine
 * services are annotation-free, so each adapter builds them from its own inputs. A service that needs a
 * <em>live</em> policy ({@link ThreadDumpService}) is given the <em>concrete</em>
 * {@link QuarkusExposurePolicy} bean — mirroring Spring injecting the concrete {@code BootUiExposure}
 * rather than the SPI interface — so adding more {@code ExposurePolicy} beans later can never make this
 * wiring ambiguous. A service that needs only <em>static</em> settings ({@link HeapDumpService}) instead
 * receives an immutable {@link HeapDumpSettings} record mapped inline from {@code bootui.heap-dump.*},
 * matching the Spring factory's defaults. The {@link MemoryReportProvider} follows the live-policy shape
 * too: it is given the concrete {@link QuarkusMemoryRuntimeConfig} bean so future
 * {@code MemoryRuntimeConfig} beans can never make this wiring ambiguous.</p>
 */
@ApplicationScoped
public class BootUiEngineProducer {

    @Produces
    @Singleton
    public ThreadDumpService threadDumpService(QuarkusExposurePolicy exposure) {
        return new ThreadDumpService(exposure);
    }

    @Produces
    @Singleton
    public MemoryReportProvider memoryReportProvider(QuarkusMemoryRuntimeConfig runtimeConfig) {
        return new MemoryReportProvider(runtimeConfig);
    }

    /**
     * The HTTP Probe service over the Quarkus {@link QuarkusServerPortSupplier}. Mirrors the live-policy
     * shape: the concrete {@link QuarkusServerPortSupplier} bean is injected (not the
     * {@link io.github.jdubois.bootui.spi.ServerPortSupplier} interface) so adding another supplier later
     * can never make this wiring ambiguous. The engine probes the application's own loopback port, read
     * live on every probe.
     */
    @Produces
    @Singleton
    public HttpProbeService httpProbeService(QuarkusServerPortSupplier serverPort) {
        return new HttpProbeService(serverPort);
    }

    /**
     * The Metrics service over Micrometer. Micrometer is a sanctioned {@code bootui-engine} dependency, so
     * its API is always on the classpath, but a {@link MeterRegistry} <em>bean</em> exists only when the
     * application adds a {@code quarkus-micrometer} registry. The registry is therefore resolved live per
     * request through an {@link Instance} (mirroring the Spring adapter's {@code ObjectProvider} handle):
     * absent &rarr; {@code null} &rarr; the engine renders the panel as unavailable; present &rarr; the live
     * composite registry is read on every report. The meter-visibility predicate is the shared engine
     * {@link MeterSelfFilter} built from the same transform-side {@link SelfTelemetryClassifier} the Traces
     * read model uses (honoring {@code bootui.monitoring.exclude-self} and {@code bootui.path}), so the panel
     * never reports BootUI's own {@code /bootui/**} traffic — exactly as the Spring adapter feeds
     * {@code BootUiSelfDataFilter::shouldIncludeMeter}.
     */
    @Produces
    @Singleton
    public MetricsReportProvider metricsReportProvider(Instance<MeterRegistry> registries, Config config) {
        MeterSelfFilter meterFilter = new MeterSelfFilter(transformClassifier(config));
        return new MetricsReportProvider(() -> resolveRegistry(registries), meterFilter::shouldIncludeMeter);
    }

    static MeterRegistry resolveRegistry(Instance<MeterRegistry> registries) {
        if (registries.isUnsatisfied()) {
            return null;
        }
        try {
            return registries.get();
        } catch (AmbiguousResolutionException ex) {
            // Multiple registries (e.g. several Micrometer backends): pick the first, like the Spring adapter.
            return registries.stream().findFirst().orElse(null);
        }
    }

    private static SelfTelemetryClassifier transformClassifier(Config config) {
        boolean excludeSelf = config.getOptionalValue("bootui.monitoring.exclude-self", Boolean.class)
                .orElse(Boolean.TRUE);
        String path = config.getOptionalValue("bootui.path", String.class).orElse("/bootui");
        String apiPath =
                config.getOptionalValue("bootui.api-path", String.class).orElse("/bootui/api");
        return new SelfTelemetryClassifier(excludeSelf, path, apiPath);
    }

    @Produces
    @Singleton
    public HeapDumpService heapDumpService(Config config) {
        HeapDumpSettings settings = new HeapDumpSettings(
                config.getOptionalValue("bootui.heap-dump.output-dir", String.class)
                        .orElse(".bootui/heap-dumps"),
                config.getOptionalValue("bootui.heap-dump.capture-enabled", Boolean.class)
                        .orElse(Boolean.TRUE),
                config.getOptionalValue("bootui.heap-dump.allow-raw-download", Boolean.class)
                        .orElse(Boolean.FALSE),
                config.getOptionalValue("bootui.heap-dump.max-dumps", Integer.class)
                        .orElse(5),
                config.getOptionalValue("bootui.heap-dump.max-classes", Integer.class)
                        .orElse(1000),
                config.getOptionalValue("bootui.heap-dump.top-classes", Integer.class)
                        .orElse(25));
        return new HeapDumpService(settings);
    }

    /**
     * The Loggers service over the Quarkus {@link QuarkusLoggerProvider} (the JBoss LogManager). Mirrors
     * the Spring {@code bootUiLoggersService} factory's two-predicate split: a read-visibility predicate
     * that honors {@code bootui.monitoring.exclude-self} (default {@code true}, hiding BootUI's own
     * loggers from the panel) and an independent write guard pinned to BootUI-owned loggers regardless of
     * that preference, so a read toggle can never fail the write open. The internal-package boundary is
     * the shared engine {@link InternalPackageMatcher} fed the Quarkus adapter's own packages (plus the
     * shared {@code core}), exactly as the Spring adapter feeds its {@code autoconfigure} package.
     */
    @Produces
    @Singleton
    public LoggersService loggersService(Config config) {
        boolean excludeSelf = config.getOptionalValue("bootui.monitoring.exclude-self", Boolean.class)
                .orElse(Boolean.TRUE);
        InternalPackageMatcher internalPackages = new InternalPackageMatcher(
                List.of("io.github.jdubois.bootui.quarkus", "io.github.jdubois.bootui.core"));
        LoggerProvider provider = new QuarkusLoggerProvider();
        Predicate<String> readVisible = name -> !excludeSelf || !internalPackages.matchesName(name);
        Predicate<String> writeBlocked = internalPackages::matchesName;
        return new LoggersService(provider, readVisible, writeBlocked);
    }

    /**
     * The Health service over the Quarkus {@link HealthProvider}. The provider is gated by the deployment
     * processor on the {@code quarkus-smallrye-health} capability (R2), so when SmallRye Health is absent there
     * is no {@code HealthProvider} bean: {@code providers.isUnsatisfied()} is {@code true} and the service is
     * built with a {@code null} provider, which makes the engine render the DISABLED root with
     * {@link QuarkusHealthGuidance}'s setup steps. The service is produced <em>unconditionally</em> (it holds no
     * SmallRye types) so {@code HealthResource} is wired and the panel renders its guidance on every platform.
     * {@code isUnsatisfied()} (rather than {@code isResolvable()}) is used deliberately: an impossible-but-future
     * ambiguous {@code HealthProvider} surfaces loudly via {@link Instance#get()} instead of silently disabling.
     */
    @Produces
    @Singleton
    public HealthService healthService(Instance<HealthProvider> healthProviders) {
        HealthProvider provider = healthProviders.isUnsatisfied() ? null : healthProviders.get();
        return new HealthService(provider, QuarkusHealthGuidance.INSTANCE);
    }

    /**
     * The Architecture (ArchUnit) hygiene scanner over the {@link QuarkusBasePackageProvider}. Mirrors the
     * live-policy shape: the concrete {@link QuarkusBasePackageProvider} bean is injected (not the
     * {@link io.github.jdubois.bootui.spi.BasePackageProvider} interface) so adding another provider later
     * can never make this wiring ambiguous. The base packages are read <em>live</em> on every scan through
     * the supplier (never snapshotted at construction), exactly as the Spring adapter binds its scanner over
     * {@code AutoConfigurationPackages}; the ArchUnit import itself runs only on the explicit
     * {@code POST /scan} action, never at construction.
     */
    @Produces
    @Singleton
    public ArchitectureScanner architectureScanner(QuarkusBasePackageProvider basePackages) {
        return ArchitectureScanner.usingClasspath(basePackages::basePackages, Clock.systemUTC());
    }

    /**
     * The shared advisor dismissed-rules store. Resolves the same {@code .bootui/boot-ui.yml} path the Spring
     * adapter's {@code bootUiDismissedRulesStore} factory builds: the parent directory of
     * {@code bootui.overrides-file} (default {@code .bootui/application-bootui.properties}), falling back to
     * {@code .bootui}. The store is pure {@code java.nio} and shared by every ArchUnit advisor, so the
     * Architecture dismiss controls round-trip identically on both platforms.
     */
    @Produces
    @Singleton
    public DismissedRulesStore dismissedRulesStore(Config config) {
        String overridesFile = config.getOptionalValue("bootui.overrides-file", String.class)
                .orElse(".bootui/application-bootui.properties");
        Path parent = (overridesFile != null && !overridesFile.isBlank())
                ? Paths.get(overridesFile).getParent()
                : null;
        String dir = (parent != null) ? parent.toString() : ".bootui";
        return new DismissedRulesStore(Paths.get(dir, "boot-ui.yml"));
    }
}
