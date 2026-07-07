package io.github.jdubois.bootui.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.activity.ActivityInstanceIds;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityStoreFactory;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.engine.cache.CacheService;
import io.github.jdubois.bootui.engine.config.ConfigService;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.devservices.DevServicesReportService;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import io.github.jdubois.bootui.engine.github.DefaultGitHubTokenProvider;
import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscoverySource;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import io.github.jdubois.bootui.engine.metrics.MeterSelfFilter;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.pentesting.PentestingScanner;
import io.github.jdubois.bootui.engine.quarkusapp.QuarkusAppScanner;
import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.quarkus.beans.QuarkusBeanProvider;
import io.github.jdubois.bootui.quarkus.config.QuarkusConfigProvider;
import io.github.jdubois.bootui.quarkus.health.QuarkusHealthGuidance;
import io.github.jdubois.bootui.quarkus.hibernate.QuarkusHibernatePropertyLookup;
import io.github.jdubois.bootui.quarkus.logging.QuarkusLoggerProvider;
import io.github.jdubois.bootui.quarkus.mappings.QuarkusMappingProvider;
import io.github.jdubois.bootui.quarkus.pentesting.QuarkusPentestingObservationCollector;
import io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppSnapshotProviderImpl;
import io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledTaskProvider;
import io.github.jdubois.bootui.quarkus.security.QuarkusSecuritySnapshotProviderImpl;
import io.github.jdubois.bootui.quarkus.web.GitHubApiClient;
import io.github.jdubois.bootui.quarkus.web.QuarkusGitHubSettings;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.FlywayProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.sql.DataSource;
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

    /**
     * MicroProfile OpenAPI's {@code @Operation} annotation, brought onto the classpath by
     * {@code quarkus-smallrye-openapi}. Its presence is the Quarkus analogue of the Spring adapter's
     * {@code ClassUtils.isPresent("io.swagger.v3.oas.annotations.Operation", ...)} probe: both signal that
     * OpenAPI documentation annotations are available for RAPI-DOC-001/002/003 to evaluate.
     */
    private static final String MP_OPENAPI_OPERATION_ANNOTATION =
            "org.eclipse.microprofile.openapi.annotations.Operation";

    private static final boolean MP_OPENAPI_PRESENT = isMpOpenApiPresent();

    private static boolean isMpOpenApiPresent() {
        try {
            Class.forName(MP_OPENAPI_OPERATION_ANNOTATION, false, BootUiEngineProducer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

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
     * The Memory advisor scanner over the shared {@link ThreadDumpService}. Always available — the
     * scanner reads only JMX management beans (heap/GC/threads/class-loading) present on every JVM, so
     * no capability gate or optional dependency is involved. {@code @Singleton} matches the scanner's
     * cross-scan GC-trend state (a {@code previousGcSample} baseline), mirroring the
     * {@link ArchitectureScanner} and {@link ThreadDumpService} producers; the heap-content histogram
     * forces a full GC, so the {@code POST /scan} resource method runs blocking, off the event loop.
     */
    @Produces
    @Singleton
    public MemoryScanner memoryScanner(ThreadDumpService threadDumpService) {
        return MemoryScanner.create(threadDumpService, Clock.systemUTC());
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
     * The Quarkus-only HTTP exchange ring buffer fed by the Vert.x capture filter — Quarkus has no
     * Actuator {@code HttpExchangeRepository}, so this is the capture source for the HTTP Exchanges and
     * Live Activity panels. Capacity bounds memory ({@code bootui.http-exchanges.max-exchanges}, default
     * 200, unified with the Spring adapter's {@code BootUiProperties.HttpExchanges.maxExchanges}); the
     * buffer caps and reverses, the engine service masks. A singleton so writes and reads share one
     * bounded buffer.
     */
    @Produces
    @Singleton
    public HttpExchangeBuffer httpExchangeBuffer(Config config) {
        int maxExchanges = config.getOptionalValue("bootui.http-exchanges.max-exchanges", Integer.class)
                .orElse(200);
        return new HttpExchangeBuffer(maxExchanges);
    }

    /**
     * The Quarkus-only security-event ring buffer fed by the CDI security-event observer — Quarkus has no
     * Actuator {@code AuditEventRepository}, so this is the capture source for the Security Logs panel.
     * Capacity bounds memory ({@code bootui.security-logs.max-logs}, default 500, matching the Spring panel
     * cap); the buffer caps and reverses, the engine service masks. A singleton so writes and reads share
     * one bounded buffer.
     */
    @Produces
    @Singleton
    public SecurityEventBuffer securityEventBuffer(Config config) {
        int capacity = config.getOptionalValue("bootui.security-logs.max-logs", Integer.class)
                .orElse(500);
        return new SecurityEventBuffer(capacity);
    }

    /**
     * The framework-neutral Email Viewer capture service, always produced (like the engine
     * {@code CacheService}) so {@code GET /bootui/api/email} never fails even when {@code quarkus-mailer} is
     * absent; the resource then reports the panel unavailable via {@link QuarkusPanelAvailability#EMAIL_PRESENT_KEY}.
     * When {@code quarkus-mailer} <em>is</em> present, {@code QuarkusEmailCapture} (registered by the
     * deployment {@code registerEmail} build step) observes {@code io.quarkus.mailer.SentMail} and feeds this
     * service's {@link EmailStore}. Capacity bounds memory ({@code bootui.email.max-entries}, default 100,
     * matching the Spring panel cap).
     *
     * <p>Unlike Spring's {@code bootui.email.dev-trap} (which intercepts <em>before</em> the send and can block
     * it), Quarkus fires {@code SentMail} <em>after</em> the send, so BootUI cannot trap the message. Instead the
     * service's dev-trap flag is mapped to the effective {@code quarkus.mailer.mock} value (which defaults to
     * {@code true} in dev/test — the only launch modes where the console is wired): mock mode records the message
     * without handing it to a real transport, so {@code sent=false} is the honest report, exactly matching what
     * the engine's {@code sent=!devTrapEnabled} already computes.</p>
     */
    @Produces
    @Singleton
    public EmailCaptureService emailCaptureService(QuarkusExposurePolicy exposure, Config config) {
        int maxEntries = config.getOptionalValue("bootui.email.max-entries", Integer.class)
                .orElse(100);
        boolean mock = config.getOptionalValue("quarkus.mailer.mock", Boolean.class)
                .orElseGet(() -> LaunchMode.current().isDevOrTest());
        return new EmailCaptureService(new EmailStore(maxEntries), exposure, mock);
    }

    /**
     * The Live Activity persistence settings, mapped once from {@code bootui.activity.persistence.*}
     * (matching the Spring adapter's {@code BootUiProperties.ActivityPersistence} defaults). Exposed as
     * its own producer — not inlined into the {@link SwitchableActivityStore} producer — because two
     * independent consumers must agree on the exact same resolved values, in particular the same resolved
     * {@code instanceId}: the {@link SwitchableActivityStore} bean bakes it into the durable store's own
     * query/prune scope, and {@code QuarkusActivityCapture} stamps it onto every entry its capture
     * coordinator captures. {@code @Singleton} guarantees both see one, consistently resolved value — in
     * particular, a generated instance id is computed exactly once per process, not independently by each
     * consumer.
     */
    @Produces
    @Singleton
    public ActivityPersistenceSettings activityPersistenceSettings(Config config) {
        boolean enabled = config.getOptionalValue("bootui.activity.persistence.enabled", Boolean.class)
                .orElse(Boolean.FALSE);
        String dataSourceModeValue = config.getOptionalValue(
                        "bootui.activity.persistence.data-source-mode", String.class)
                .orElse("SHARED");
        ActivityPersistenceSettings.DataSourceMode dataSourceMode = "DEDICATED".equalsIgnoreCase(dataSourceModeValue)
                ? ActivityPersistenceSettings.DataSourceMode.DEDICATED
                : ActivityPersistenceSettings.DataSourceMode.SHARED;
        String instanceId = ActivityInstanceIds.resolveOrDefault(
                config.getOptionalValue("bootui.activity.persistence.instance-id", String.class)
                        .orElse(null),
                config.getOptionalValue("quarkus.application.name", String.class)
                        .orElse(null));
        return new ActivityPersistenceSettings(
                enabled,
                dataSourceMode,
                config.getOptionalValue("bootui.activity.persistence.dedicated-jdbc-url", String.class)
                        .orElse(null),
                config.getOptionalValue("bootui.activity.persistence.dedicated-username", String.class)
                        .orElse(null),
                config.getOptionalValue("bootui.activity.persistence.dedicated-password", String.class)
                        .orElse(null),
                config.getOptionalValue("bootui.activity.persistence.dedicated-driver-class-name", String.class)
                        .orElse(null),
                config.getOptionalValue("bootui.activity.persistence.table-name", String.class)
                        .orElse("bootui_activity"),
                config.getOptionalValue("bootui.activity.persistence.flush-interval", Duration.class)
                        .orElse(Duration.ofSeconds(5)),
                config.getOptionalValue("bootui.activity.persistence.buffer-max-entries", Integer.class)
                        .orElse(500),
                config.getOptionalValue("bootui.activity.persistence.retention", Duration.class)
                        .orElse(Duration.ofDays(7)),
                instanceId,
                config.getOptionalValue("bootui.activity.persistence.capture-interval", Duration.class)
                        .orElse(Duration.ofSeconds(2)));
    }

    /**
     * The Live Activity durable store. Produced <em>unconditionally</em> (like the Cache/Flyway/Liquibase/
     * Connection-Pools services): {@link ActivityStoreFactory#create} itself branches on {@code
     * settings.enabled()}, returning a {@link SwitchableActivityStore} wrapping a bare {@code
     * InMemoryActivityStore} — no background thread, connection or JDBC type touched — when persistence is
     * off, so there is no need to gate this producer on a build-time capability the way the
     * optional-dependency panels do. When enabled with {@code data-source-mode=SHARED}, the host
     * application's own {@code DataSource} bean is resolved live through {@link Instance}, mirroring
     * {@link #resolveRegistry}.
     *
     * <p>Declared to return the concrete {@link SwitchableActivityStore} type (not the {@code ActivityStore}
     * interface) so {@code LiveActivityResource} and {@code QuarkusActivityCapture} can inject the concrete
     * type and call {@code persistent()} / {@code attemptSwitchToPersistent(...)} — the same bean also
     * satisfies {@code ActivityStore} injection points, since a CDI producer's bean types include every
     * supertype of the declared return type.</p>
     *
     * <p>Unlike Spring — whose inferred-destroy-method convention auto-invokes a bean's {@code close()}
     * at context shutdown — CDI/Arc has no equivalent automatic behavior, so this store is explicitly
     * closed by {@code QuarkusActivityCapture}'s {@code ShutdownEvent} observer instead.</p>
     */
    @Produces
    @Singleton
    public SwitchableActivityStore activityStore(
            ActivityPersistenceSettings settings, Instance<DataSource> dataSources) {
        return ActivityStoreFactory.create(settings, () -> resolveDataSource(dataSources));
    }

    /**
     * Resolves the shared {@code DataSource} to reuse for Live Activity persistence, mirroring {@link
     * #resolveRegistry}. Public (unlike most other package-private producer helpers) since {@code
     * LiveActivityResource} also calls this directly to resolve {@code dataSourceAvailable} and to feed
     * the "Use the existing datasource" switch action — the Quarkus analogue of the Spring adapter's
     * public {@code resolveActivityDataSource}.
     */
    public static DataSource resolveDataSource(Instance<DataSource> dataSources) {
        if (dataSources.isUnsatisfied()) {
            return null;
        }
        try {
            return dataSources.get();
        } catch (AmbiguousResolutionException ex) {
            // Multiple DataSource beans (e.g. several named datasources): pick the first, like the Spring adapter.
            return dataSources.stream().findFirst().orElse(null);
        }
    }

    /**
     * The Metrics service over Micrometer. Micrometer is a sanctioned {@code bootui-engine} dependency, so
     * its API is always on the classpath, but a {@link MeterRegistry} <em>bean</em> exists only when the
     * application adds a {@code quarkus-micrometer} registry. The registry is therefore resolved live per
     * request through an {@link Instance} (mirroring the Spring adapter's {@code ObjectProvider} handle):
     * absent &rarr; {@code null} &rarr; the engine renders the panel as unavailable; present &rarr; the live
     * composite registry is read on every report. The meter-visibility predicate is the shared engine
     * {@link MeterSelfFilter} built from the single {@link SelfTelemetryClassifier} bean produced in
     * {@link BootUiTelemetryProducer} (honoring {@code bootui.monitoring.exclude-self} and
     * {@code bootui.path}) — the same instance the Traces read model and the capture-side span processor
     * use — so the panel never reports BootUI's own {@code /bootui/**} traffic, exactly as the Spring
     * adapter feeds {@code BootUiSelfDataFilter::shouldIncludeMeter}.
     */
    @Produces
    @Singleton
    public MetricsReportProvider metricsReportProvider(
            Instance<MeterRegistry> registries, SelfTelemetryClassifier selfClassifier) {
        MeterSelfFilter meterFilter = new MeterSelfFilter(selfClassifier);
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
     * The shared Log Tail ring buffer fed by {@code QuarkusLogTailHandler} (the JBoss LogManager) and read
     * by {@code LogTailResource}. Mirrors the Spring adapter's buffer: capped to {@code 500} lines and an
     * approximate byte budget ({@code bootui.log-tail.max-bytes}, default unbounded) so a long-lived dev
     * process stays bounded while a normal short-lined tail keeps all 500 lines.
     */
    @Produces
    @Singleton
    public LogTailBuffer logTailBuffer(Config config) {
        long maxBytes =
                config.getOptionalValue("bootui.log-tail.max-bytes", Long.class).orElse(0L);
        return new LogTailBuffer(LogTailBuffer.DEFAULT_MAX_LINES, maxBytes);
    }

    /**
     * The shared, bounded {@link ExceptionStore} fed by {@code QuarkusExceptionLogHandler} (the JBoss
     * LogManager) and {@code QuarkusExceptionFailureFilter} (the Vert.x failure handler), read by
     * {@code ExceptionsResource}. Mirrors the Spring adapter's store: cause-chain identity dedup keeps a
     * single logical failure seen by both feeders from counting twice, caps bound memory, and host base
     * packages flag application frames. A singleton so writes and reads share one store.
     */
    @Produces
    @Singleton
    public ExceptionStore exceptionStore(
            Config config, QuarkusBasePackageProvider basePackages, Instance<SpanEnricher> spanEnricher) {
        ExceptionStore store = new ExceptionStore(
                config.getOptionalValue("bootui.exceptions.max-groups", Integer.class)
                        .orElse(100),
                config.getOptionalValue("bootui.exceptions.max-occurrences-per-group", Integer.class)
                        .orElse(25),
                config.getOptionalValue("bootui.exceptions.max-stack-frames", Integer.class)
                        .orElse(50));
        store.setApplicationPackages(basePackages.basePackages());
        // When OpenTelemetry is present, install the span enricher so each captured exception stamps
        // bootui.exception.* on the active request span; absent OpenTelemetry it is unresolvable and the
        // store keeps the neutral no-op.
        if (spanEnricher.isResolvable()) {
            store.setSpanEnricher(spanEnricher.get());
        }
        return store;
    }

    /**
     * Display masking + DTO assembly for the Exceptions panel, shared with Spring so the wire is identical.
     */
    @Produces
    @Singleton
    public ExceptionsService exceptionsService(QuarkusExposurePolicy exposure) {
        return new ExceptionsService(exposure);
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
     * The Pentesting (local OWASP hygiene) scanner. Mirrors the Spring {@code bootUiPentestingScanner}
     * factory: the framework-neutral observation (server port + context path, plus a deliberately neutral
     * endpoint/security/config snapshot — see {@link QuarkusPentestingObservationCollector}) is collected
     * live on every scan, and the engine owns the probe methodology (synthetic loopback URI assembly +
     * GET/OPTIONS probes), firing them only on demand ({@code POST /scan}), never at construction. The
     * concrete {@link QuarkusServerPortSupplier} bean is injected (not the
     * {@link io.github.jdubois.bootui.spi.ServerPortSupplier} interface) so adding another supplier later
     * can never make this wiring ambiguous, exactly as the HTTP Probe and Architecture producers do.
     */
    @Produces
    @Singleton
    public PentestingScanner pentestingScanner(QuarkusServerPortSupplier serverPort, Config config) {
        QuarkusPentestingObservationCollector collector = new QuarkusPentestingObservationCollector(config, serverPort);
        return PentestingScanner.usingObservation(collector::collect, Clock.systemUTC());
    }

    /**
     * The Quarkus-native Security advisor scanner over a config-driven {@link QuarkusSecuritySnapshotProviderImpl}.
     * Evaluates a Quarkus ruleset (Elytron/OIDC/HTTP auth, TLS, CORS, headers) into the shared report.
     */
    @Produces
    @Singleton
    public QuarkusSecurityScanner quarkusSecurityScanner(Config config) {
        return QuarkusSecurityScanner.usingSnapshot(
                new QuarkusSecuritySnapshotProviderImpl(config)::snapshot, Clock.systemUTC());
    }

    /**
     * The Quarkus-native application advisor scanner over a config-driven {@link QuarkusAppSnapshotProviderImpl}.
     * Evaluates a Quarkus idiom ruleset (CDI scopes, @ConfigProperty, reactive/blocking, profiles) into the
     * shared Spring advisor report.
     */
    @Produces
    @Singleton
    public QuarkusAppScanner quarkusAppScanner(Config config) {
        return QuarkusAppScanner.usingSnapshot(new QuarkusAppSnapshotProviderImpl(config)::snapshot, Clock.systemUTC());
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
     * The REST API advisor over the application's JAX-RS resources. Mirrors {@link #architectureScanner}: the
     * shared engine {@link RestApiScanner} imports the host classes with ArchUnit bounded to the build-time
     * discovered base packages and runs the same curated ruleset (the engine now models JAX-RS resources
     * alongside Spring controllers and skips the few Spring-only rules honestly). The OpenAPI documentation
     * rules (RAPI-DOC-001/002/003) probe for the framework-neutral MicroProfile OpenAPI {@code @Operation}
     * annotation on the classpath — brought in by {@code quarkus-smallrye-openapi} — the same way the Spring
     * adapter probes for Swagger's {@code @Operation}; the import runs only on POST /scan.
     */
    @Produces
    @Singleton
    public RestApiScanner restApiScanner(QuarkusBasePackageProvider basePackages) {
        return RestApiScanner.usingClasspath(basePackages::basePackages, () -> MP_OPENAPI_PRESENT, Clock.systemUTC());
    }

    /**
     * The Beans panel over the Arc/CDI container. Mirrors the live-policy shape: the concrete
     * {@link QuarkusBeanProvider} bean is injected (not the {@link io.github.jdubois.bootui.spi.BeanProvider}
     * interface) so adding another provider later can never make this wiring ambiguous. The provider holds
     * the {@link BeanManager} and enumerates beans live on every request; the engine {@link BeansService}
     * only sorts, classification/free-text filters and pages — exactly as the Spring adapter builds its
     * {@code BeansService} over the Actuator-backed {@code SpringBeanProvider}.
     */
    @Produces
    @Singleton
    public QuarkusBeanProvider quarkusBeanProvider(BeanManager beanManager) {
        return new QuarkusBeanProvider(beanManager);
    }

    @Produces
    @Singleton
    public BeansService beansService(QuarkusBeanProvider beanProvider) {
        return new BeansService(beanProvider);
    }

    /**
     * The Mappings panel service. The engine {@link MappingsService} owns only the framework-neutral sort,
     * free-text query and paging; the flattening + BootUI self-data filtering happen at build time in the
     * deployment processor's {@code registerMappings} step (where both the request path and the resource
     * class FQN are available). The concrete {@link QuarkusMappingProvider} is injected (not the
     * {@code MappingProvider} interface) so adding another provider later can never make this wiring
     * ambiguous — exactly as the Spring adapter builds its {@code MappingsService} over the Actuator-backed
     * {@code SpringMappingProvider}.
     */
    @Produces
    @Singleton
    public MappingsService mappingsService(QuarkusMappingProvider mappingProvider) {
        return new MappingsService(mappingProvider);
    }

    /**
     * The Configuration + Profile Diff panel service. The cache-/Jackson-free engine {@link ConfigService}
     * masks, sorts, filters, pages and profile-groups the raw entries from {@link QuarkusConfigProvider}
     * (SmallRye Config), honoring the live {@link QuarkusExposurePolicy} — exactly as the Spring adapter
     * builds it over {@code SpringConfigProvider}. The concrete policy impl is injected (not the SPI) so
     * adding another policy later can't make resolution ambiguous. Read-only on Quarkus: the Spring
     * runtime-override write path is not ported.
     */
    @Produces
    @Singleton
    public ConfigService configService(QuarkusConfigProvider provider, QuarkusExposurePolicy exposure) {
        return new ConfigService(provider, exposure);
    }

    /**
     * The GitHub dashboard service over a Jackson-2 {@link GitHubApiClient}. The shared engine
     * {@link GitHubDashboardService} is framework- and JSON-library-free; this factory mirrors the Spring
     * adapter's {@code GitHubController} composition root, reading the same {@code bootui.github.*} keys (with
     * the same defaults) from MicroProfile {@link Config} and feeding the engine an already-wired client.
     *
     * <p>The host allow-list is read once and passed to <em>both</em> the engine
     * {@link GitHubDashboardConfig} (which the engine consults during repository detection) and the client's
     * {@link QuarkusGitHubSettings} (which the client enforces before issuing any request), exactly as Spring
     * shares one {@code BootUiProperties.GitHub}. {@code Arrays.asList(...)} is used (not {@code List.of(...)})
     * to stay null-safe, matching the Spring factory. The credential lookup reuses the framework-free shared
     * {@link DefaultGitHubTokenProvider} (env tokens + {@code gh} CLI). No network call happens at construction
     * or on render: only the explicit {@code POST /bootui/api/github/refresh} action calls GitHub.</p>
     */
    @Produces
    @Singleton
    public GitHubDashboardService gitHubDashboardService(Config config) {
        boolean apiEnabled = config.getOptionalValue("bootui.github.api-enabled", Boolean.class)
                .orElse(Boolean.TRUE);
        Duration requestTimeout = config.getOptionalValue("bootui.github.request-timeout", Duration.class)
                .orElse(Duration.ofSeconds(5));
        int maxPullRequests = config.getOptionalValue("bootui.github.max-pull-requests", Integer.class)
                .orElse(10);
        int maxIssues = config.getOptionalValue("bootui.github.max-issues", Integer.class)
                .orElse(25);
        int maxWorkflowRuns = config.getOptionalValue("bootui.github.max-workflow-runs", Integer.class)
                .orElse(20);
        int quotaSafetyThreshold = config.getOptionalValue("bootui.github.quota-safety-threshold", Integer.class)
                .orElse(10);
        int maxApiCalls = config.getOptionalValue("bootui.github.max-api-calls", Integer.class)
                .orElse(17);
        int maxSecurityAlerts = config.getOptionalValue("bootui.github.max-security-alerts", Integer.class)
                .orElse(50);
        List<String> allowedApiHosts = gitHubAllowedApiHosts(config);

        QuarkusGitHubSettings settings = new QuarkusGitHubSettings(
                requestTimeout,
                maxPullRequests,
                maxIssues,
                maxWorkflowRuns,
                quotaSafetyThreshold,
                maxApiCalls,
                maxSecurityAlerts,
                allowedApiHosts);
        GitHubApiClient client = new GitHubApiClient(
                settings,
                HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
                new ObjectMapper(),
                DefaultGitHubTokenProvider.create());
        return GitHubDashboardService.using(
                Path.of(System.getProperty("user.dir", ".")),
                new GitHubDashboardConfig(apiEnabled, allowedApiHosts),
                client);
    }

    /**
     * Reads {@code bootui.github.allowed-api-hosts} (comma-separated) from MicroProfile {@link Config}, falling
     * back to {@code api.github.com}. {@code Arrays.asList} keeps it null-safe, matching the Spring adapter's
     * {@code Arrays.asList(properties.getGithub().getAllowedApiHosts())}.
     */
    static List<String> gitHubAllowedApiHosts(Config config) {
        String[] hosts = config.getOptionalValue("bootui.github.allowed-api-hosts", String[].class)
                .orElse(new String[] {"api.github.com"});
        return Arrays.asList(hosts);
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

    /**
     * The Hibernate (ORM mapping) advisor scanner. Produced <em>unconditionally</em> because it holds no
     * {@code jakarta.persistence} type: the JPA-importing entity discovery lives behind the gated
     * {@link EntityDiscoverySource} that {@link BootUiHibernateProducer} produces only when
     * {@code quarkus-hibernate-orm} is present (R2). When that source is unsatisfied the scanner is given a
     * supplier yielding an {@link EntityDiscovery#empty(String) empty discovery}, so {@code POST /scan}
     * renders a DISABLED report instead of failing.
     *
     * <p>Configuration is read through {@link QuarkusHibernatePropertyLookup}, which maps the engine rules'
     * Spring/native-Hibernate keys onto the {@code quarkus.hibernate-orm.*} namespace (and neutralizes the
     * Spring-only Open-Session-in-View concern). The active profiles come from {@link SmallRyeConfig}, exactly
     * as {@link QuarkusApplicationInfo#activeProfiles()} resolves them — never the {@code quarkus.profile}
     * key — so the production-profile-sensitive rules behave identically to Spring.</p>
     */
    @Produces
    @Singleton
    public HibernateScanner hibernateScanner(Instance<EntityDiscoverySource> sources, Config config) {
        Supplier<EntityDiscovery> discovery;
        if (sources.isUnsatisfied()) {
            discovery = () -> EntityDiscovery.empty("Hibernate ORM is not configured on this Quarkus application.");
        } else {
            EntityDiscoverySource source = sources.get();
            discovery = source::discover;
        }
        return HibernateScanner.using(
                discovery, new QuarkusHibernatePropertyLookup(config), () -> activeProfiles(config), Clock.systemUTC());
    }

    /**
     * The Cache panel service. Produced <em>unconditionally</em> because it holds no {@code io.quarkus.cache}
     * type: the cache-API-importing {@link CacheProvider} lives behind the gated {@link BootUiCacheProducer}
     * that is wired only when the {@code CACHE} capability is present (R2). When that provider is unsatisfied
     * the engine is given a {@code null} provider, so {@code GET /cache} renders the panel unavailable
     * and {@code POST /clear} reports it unavailable instead of failing.
     *
     * <p>Cache metrics are read live from the same {@link MeterRegistry} the Metrics panel uses (present only
     * when the application adds a {@code quarkus-micrometer} registry), through the identical
     * {@link MeterSelfFilter} self-visibility predicate built from the shared {@link SelfTelemetryClassifier}
     * bean, so BootUI's own meters stay hidden — exactly as the Spring adapter feeds
     * {@code BootUiSelfDataFilter::shouldIncludeMeter}.</p>
     */
    @Produces
    @Singleton
    public CacheService cacheService(
            Instance<CacheProvider> cacheProviders,
            Instance<MeterRegistry> registries,
            SelfTelemetryClassifier selfClassifier) {
        CacheProvider provider = cacheProviders.isUnsatisfied() ? null : cacheProviders.get();
        MeterSelfFilter meterFilter = new MeterSelfFilter(selfClassifier);
        return new CacheService(provider, () -> resolveRegistry(registries), meterFilter::shouldIncludeMeter);
    }

    /**
     * The Flyway panel service. Produced <em>unconditionally</em> because it holds no {@code org.flywaydb.*}
     * type: the Flyway-API-importing {@link FlywayProvider} lives behind the gated {@link BootUiFlywayProducer}
     * that is wired only when the {@code FLYWAY} capability is present (R2). When that provider is unsatisfied
     * the engine is given a {@code null} provider, so {@code GET /flyway/migrations} renders the panel
     * unavailable ({@code flywayPresent=false}) and the {@code migrate}/{@code clean} actions report it
     * unavailable instead of failing.
     */
    @Produces
    @Singleton
    public FlywayService flywayService(Instance<FlywayProvider> flywayProviders) {
        FlywayProvider provider = flywayProviders.isUnsatisfied() ? null : flywayProviders.get();
        return new FlywayService(provider);
    }

    /**
     * The Liquibase panel service. Produced <em>unconditionally</em> because it holds no {@code liquibase} /
     * {@code io.quarkus.liquibase} type: the liquibase-API-importing {@link LiquibaseProvider} lives behind the
     * gated {@link BootUiLiquibaseProducer} that is wired only when the {@code LIQUIBASE} capability is present
     * (R2). When that provider is unsatisfied the engine is given a {@code null} provider, so
     * {@code GET /liquibase/changesets} renders the panel unavailable and {@code POST /update} reports it
     * unavailable instead of failing.
     */
    @Produces
    @Singleton
    public LiquibaseService liquibaseService(Instance<LiquibaseProvider> liquibaseProviders) {
        LiquibaseProvider provider = liquibaseProviders.isUnsatisfied() ? null : liquibaseProviders.get();
        return new LiquibaseService(provider);
    }

    /**
     * The Database Connection Pools panel service. Produced <em>unconditionally</em> because it holds no
     * {@code io.agroal} type: the Agroal-API-importing {@link ConnectionPoolProvider} lives behind the gated
     * {@link BootUiAgroalProducer} that is wired only when the {@code AGROAL} capability is present (R2). When
     * that provider is unsatisfied the engine is given a {@code null} provider, so
     * {@code GET /database-connection-pools/pools} renders the panel unavailable (empty report).
     *
     * <p>It is given the concrete {@link QuarkusExposurePolicy} bean (not the {@code ExposurePolicy} SPI
     * interface) so the engine masks the JDBC URL and pool username live — mirroring the Spring backend config
     * passing {@code BootUiExposure} — and so adding more {@code ExposurePolicy} beans later can never make
     * this wiring ambiguous.</p>
     */
    @Produces
    @Singleton
    public ConnectionPoolService connectionPoolService(
            Instance<ConnectionPoolProvider> providers, QuarkusExposurePolicy exposure) {
        ConnectionPoolProvider provider = providers.isUnsatisfied() ? null : providers.get();
        return new ConnectionPoolService(provider, exposure);
    }

    private static List<String> activeProfiles(Config config) {
        try {
            return List.copyOf(config.unwrap(SmallRyeConfig.class).getProfiles());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * The OSV.dev vulnerability scanner over the JDK {@link java.net.http.HttpClient} and Jackson 2. It
     * follows the engine's <em>static settings record</em> extraction template: vulnerabilities has no live
     * UI override / re-bind path, so the immutable {@link QuarkusVulnerabilitySettings} is mapped once from
     * {@code bootui.vulnerabilities.*} (matching the Spring factory's defaults). The companion
     * {@link QuarkusDependencyProvider} (the build-time-captured local inventory) is a plain {@code @Singleton}
     * bean rather than a producer-method bean. The scanner is produced (not annotated as a bean itself) so its
     * concrete type can be injected into {@code VulnerabilitiesResource} without making CDI resolution
     * ambiguous — and it is never invoked except by the user-initiated {@code POST /scan}.
     */
    @Produces
    @Singleton
    public OsvVulnerabilityScanner osvVulnerabilityScanner(Config config) {
        return new OsvVulnerabilityScanner(QuarkusVulnerabilitySettings.from(config));
    }

    /**
     * The Scheduled Tasks service over the Quarkus {@link QuarkusScheduledTaskProvider}. Mirrors the Spring
     * {@code bootUiScheduledTasksService} factory: the engine {@link ScheduledTasksService} owns only the
     * framework-neutral sort + {@code schedulingPresent}/{@code total} wrapping, while the provider (the
     * adapter) maps the build-time-captured {@code @Scheduled} metadata to the neutral DTO and self-filters.
     *
     * <p>The service is produced <em>unconditionally</em> (it holds no scheduler types), so
     * {@code ScheduledResource} is wired and the panel renders on every platform; when {@code quarkus-scheduler}
     * is absent the provider's captured-tasks {@code Instance} is unsatisfied, so it reports unavailable and
     * the engine renders an empty {@code schedulingPresent=false} report. The concrete
     * {@link QuarkusScheduledTaskProvider} is injected (not the {@code ScheduledTaskProvider} interface) so
     * adding another provider later can never make this wiring ambiguous, exactly as the other producers do.
     */
    @Produces
    @Singleton
    public ScheduledTasksService scheduledTasksService(QuarkusScheduledTaskProvider provider) {
        return new ScheduledTasksService(provider);
    }

    /**
     * The Dev Services panel report assembler. The engine {@link DevServicesReportService} owns only the
     * framework-neutral sort + count + wrap; {@link io.github.jdubois.bootui.quarkus.devservices.QuarkusDevServicesProvider}
     * supplies the build-time-captured
     * services (masked) and presence flags. The concrete provider is injected (not the SPI) so resolution can
     * never become ambiguous, mirroring the other producers.
     */
    @Produces
    @Singleton
    public DevServicesReportService devServicesReportService() {
        return new DevServicesReportService();
    }
}
