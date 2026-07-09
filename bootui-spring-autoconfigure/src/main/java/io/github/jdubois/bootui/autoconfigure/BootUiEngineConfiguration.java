package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.SpringBasePackageProvider;
import io.github.jdubois.bootui.autoconfigure.beans.SpringBeanProvider;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManagerBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.cache.SpringCacheProvider;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.SpringConfigProvider;
import io.github.jdubois.bootui.autoconfigure.config.SpringMemoryRuntimeConfig;
import io.github.jdubois.bootui.autoconfigure.crac.CracRuntimeInventoryCollector;
import io.github.jdubois.bootui.autoconfigure.datasource.SpringConnectionPoolProvider;
import io.github.jdubois.bootui.autoconfigure.flyway.SpringFlywayProvider;
import io.github.jdubois.bootui.autoconfigure.graalvm.HttpReachabilityMetadataRepository;
import io.github.jdubois.bootui.autoconfigure.health.SpringHealthGuidance;
import io.github.jdubois.bootui.autoconfigure.health.SpringHealthProvider;
import io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateDiscovery;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaConsumerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaProducerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.liquibase.SpringLiquibaseProvider;
import io.github.jdubois.bootui.autoconfigure.logging.SpringLoggerProvider;
import io.github.jdubois.bootui.autoconfigure.mappings.SpringMappingProvider;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.pentesting.SpringPentestingObservationCollector;
import io.github.jdubois.bootui.autoconfigure.scheduled.BootUiSchedulingConfigurer;
import io.github.jdubois.bootui.autoconfigure.scheduled.ScheduledTaskRunObservationHandler;
import io.github.jdubois.bootui.autoconfigure.scheduled.SpringScheduledTaskProvider;
import io.github.jdubois.bootui.autoconfigure.web.ActuatorMappingsController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigMetadataCatalog;
import io.github.jdubois.bootui.engine.activity.ActivityInstanceIds;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityStoreFactory;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.cache.CacheService;
import io.github.jdubois.bootui.engine.config.ConfigService;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import io.github.jdubois.bootui.engine.graalvm.GraalVmDependencySettings;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.pentesting.PentestingScanner;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Wires framework-neutral {@code bootui-engine} services into the Spring adapter.
 *
 * <p>Engine services are annotation-free so they can be produced symmetrically by the Quarkus
 * adapter ({@code @Produces}); here they are exposed as Spring beans built from the adapter's own
 * inputs. A service that needs a <em>live</em> policy (re-read on every request) injects the
 * adapter's <em>concrete</em> SPI bean (e.g. {@link BootUiExposure}) rather than the SPI interface,
 * so adding more SPI implementations later can never make this wiring ambiguous — the engine service
 * still depends only on the neutral interface. A service that needs only <em>static</em> settings
 * (read once at construction) instead receives an immutable engine record (e.g.
 * {@link io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings}) mapped inline from
 * {@link BootUiProperties} here, so no per-slice mapper bean is required.</p>
 *
 * <p><strong>Static record vs. live policy.</strong> The record shape snapshots its values when the
 * (lazy) bean is first created, so it must be used only for settings with <em>no runtime-override
 * surface</em>. That is the case for {@code bootui.heap-dump.*}: it is plain bound configuration that
 * BootUI never re-binds at runtime (changing it requires a restart). Settings an operator can change
 * live — such as {@code bootui.expose-values}/{@code bootui.mask-secrets}, which {@link BootUiExposure}
 * resolves from the live Spring {@code Environment} (and therefore the runtime override property source)
 * — must instead go through a policy interface re-read per request; snapshotting them would silently
 * ignore the override.</p>
 *
 * <p>Every engine bean is declared {@link Lazy @Lazy} so it is created only when a (also lazy) BootUI
 * controller first needs it, preserving BootUI's zero-overhead-until-opened startup design. This is
 * the canonical laziness mechanism for engine beans (self-verifying on the factory method), distinct
 * from the {@code LAZY_BEAN_NAMES} post-processor used for pre-existing infrastructure beans.
 * {@link ConditionalOnMissingBean @ConditionalOnMissingBean} lets a host application override any
 * engine service with its own bean.</p>
 */
@Configuration(proxyBeanMethods = false)
public class BootUiEngineConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ThreadDumpService bootUiThreadDumpService(BootUiExposure exposure) {
        return new ThreadDumpService(exposure);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MemoryScanner bootUiMemoryScanner(ThreadDumpService threadDumpService) {
        return MemoryScanner.create(threadDumpService, Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    BasePackageProvider bootUiBasePackageProvider(ApplicationContext applicationContext) {
        // Neutral seam shared by the ArchUnit-based advisors: the host application's own base packages,
        // resolved from AutoConfigurationPackages and re-read live on every scan (fails soft to empty).
        return new SpringBasePackageProvider(applicationContext);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ArchitectureScanner bootUiArchitectureScanner(BasePackageProvider basePackageProvider) {
        // Live policy: base packages are re-read on every scan via the BasePackageProvider SPI, and the
        // ArchUnit classpath import runs only on demand (POST /scan), never at bean construction.
        return ArchitectureScanner.usingClasspath(basePackageProvider::basePackages, Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    RestApiScanner bootUiRestApiScanner(BasePackageProvider basePackageProvider) {
        // Live policy: base packages are re-read on every scan via the shared BasePackageProvider SPI, the
        // OpenAPI annotation presence (Swagger's @Operation, honored by springdoc) is probed live, and the
        // ArchUnit import runs only on demand (POST /scan). The Quarkus adapter probes for the equivalent
        // MicroProfile OpenAPI @Operation annotation instead (see BootUiEngineProducer).
        return RestApiScanner.usingClasspath(
                basePackageProvider::basePackages,
                () -> ClassUtils.isPresent(
                        "io.swagger.v3.oas.annotations.Operation", BootUiEngineConfiguration.class.getClassLoader()),
                Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    GraalVmReadinessScanner bootUiGraalVmReadinessScanner(
            BasePackageProvider basePackageProvider, BootUiProperties properties) {
        // Reuses the shared BasePackageProvider SPI (live base packages) and snapshots the static
        // bootui.graalvm.* gating into an engine value record. Reachability-metadata repository lookups go
        // through the engine's ReachabilityMetadataRepository seam; this adapter supplies the Jackson + HTTP
        // implementation so bootui-engine stays free of any JSON library. The ArchUnit import and any
        // dependency-repository lookups run only on demand (POST /scan), never at bean construction.
        BootUiProperties.Graalvm graalvm = properties.getGraalvm();
        return GraalVmReadinessScanner.usingClasspath(
                basePackageProvider::basePackages,
                new GraalVmDependencySettings(graalvm.isRepositoryLookupEnabled(), graalvm.getMaxRepositoryLookups()),
                new HttpReachabilityMetadataRepository(graalvm.getRepositoryLookupTimeout()),
                Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    CracReadinessScanner bootUiCracReadinessScanner(
            BasePackageProvider basePackageProvider, ApplicationContext applicationContext) {
        // Reuses the shared BasePackageProvider SPI (live base packages) and reads a live runtime inventory of
        // auto-configured resources (connection pools, cache managers) through the engine's CracRuntimeInventory
        // supplier seam; this adapter supplies the Spring bean inspection so bootui-engine stays framework-neutral.
        // The ArchUnit import runs only on demand (POST /scan), never at bean construction.
        return CracReadinessScanner.usingClasspath(
                basePackageProvider::basePackages,
                () -> CracRuntimeInventoryCollector.collect(applicationContext),
                Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    PentestingScanner bootUiPentestingScanner(
            ApplicationContext applicationContext, Environment environment, BootUiProperties properties) {
        // The Spring observation (endpoint inventory, security wiring, config snapshot, server port/context path)
        // is collected live on every scan through the SpringPentestingObservationCollector adapter, so the random
        // local.server.port is read after startup; the engine owns the probe methodology (synthetic URI assembly +
        // GET/OPTIONS loopback probes) and fires them only on demand (POST /scan), never at bean construction.
        //
        // RequestMappingInfoHandlerMapping is an MVC-only type (spring-webmvc), genuinely absent from a
        // WebFlux-only classpath (bootui-spring-boot-starter-reactive does not pull in spring-webmvc).
        // The presence check must run BEFORE the .class literal below: referencing
        // RequestMappingInfoHandlerMapping.class unconditionally would resolve that constant-pool entry
        // as soon as this @Lazy factory method runs, throwing NoClassDefFoundError on such a classpath
        // (confirmed against the reactive WebFlux sample app). Passing null here is safe -
        // SpringPentestingObservationCollector treats an absent provider as an empty endpoint inventory.
        ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider = ClassUtils.isPresent(
                        "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping",
                        applicationContext.getClassLoader())
                ? applicationContext.getBeanProvider(RequestMappingInfoHandlerMapping.class)
                : null;
        SpringPentestingObservationCollector collector = new SpringPentestingObservationCollector(
                applicationContext, handlerMappingProvider, environment, properties);
        return PentestingScanner.usingObservation(collector::collect, Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    HeapDumpService bootUiHeapDumpService(BootUiProperties properties) {
        BootUiProperties.HeapDump heapDump = properties.getHeapDump();
        HeapDumpSettings settings = new HeapDumpSettings(
                heapDump.getOutputDir(),
                heapDump.isCaptureEnabled(),
                heapDump.isAllowRawDownload(),
                heapDump.getMaxDumps(),
                heapDump.getMaxClasses(),
                heapDump.getTopClasses());
        return new HeapDumpService(settings);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    HttpProbeService bootUiHttpProbeService(Environment environment) {
        // Live policy: the bound port is only known after startup (e.g. a random-port test exposes it as
        // local.server.port), so it is re-read on every probe rather than snapshotted at construction.
        return new HttpProbeService(() -> environment.getProperty(
                "local.server.port", Integer.class, environment.getProperty("server.port", Integer.class, 8080)));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MemoryReportProvider bootUiMemoryReportProvider(Environment environment) {
        // Live policy: virtual-threads and Kubernetes health-probe settings are read from the live
        // Environment (and thus the runtime override property source) on every report, not snapshotted.
        return new MemoryReportProvider(new SpringMemoryRuntimeConfig(environment));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MetricsReportProvider bootUiMetricsReportProvider(
            ObjectProvider<MeterRegistry> registries, BootUiSelfDataFilter selfDataFilter) {
        // Live handle: the MeterRegistry is resolved per request (it may be absent until metrics are
        // configured), and BootUI's own self-data filter is fed as the engine's meter-visibility predicate
        // so the console never reports its own traffic.
        return new MetricsReportProvider(() -> resolveRegistry(registries), selfDataFilter::shouldIncludeMeter);
    }

    static MeterRegistry resolveRegistry(ObjectProvider<MeterRegistry> registries) {
        try {
            return registries.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ex) {
            return registries.orderedStream().findFirst().orElse(null);
        }
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    LoggersService bootUiLoggersService(
            ObjectProvider<LoggerProvider> loggerProviders, BootUiSelfDataFilter selfDataFilter) {
        // R2 optional-dependency port: the actuator-typed SpringLoggerProvider is gated below, so this
        // always-active service resolves it through an ObjectProvider and tolerates its absence (the
        // class-absent case) by serving an empty report. BootUI's self-data filter feeds two distinct
        // predicates: read-visibility (honors bootui.monitoring.exclude-self) and a write guard pinned to
        // BootUI-owned loggers (independent of that preference, so it can never fail open).
        return new LoggersService(
                loggerProviders.getIfAvailable(),
                selfDataFilter::shouldIncludeLogger,
                selfDataFilter::isBootUiLoggerName);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    HealthService bootUiHealthService(ObjectProvider<HealthProvider> healthProviders) {
        // R2 optional-dependency port: the actuator-typed SpringHealthProvider is gated below, so this
        // always-active service resolves it through an ObjectProvider and tolerates its absence (the
        // class-absent case) by rendering the DISABLED root with setup guidance. The platform-specific
        // defaults and copy come from SpringHealthGuidance, which references only neutral DTOs so it is
        // available even when Actuator is absent (exactly when the unavailable guidance is needed).
        return new HealthService(healthProviders.getIfAvailable(), SpringHealthGuidance.INSTANCE);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MappingsService bootUiMappingsService(ObjectProvider<MappingProvider> mappingProviders) {
        // R2 optional-dependency port: the actuator-typed SpringMappingProvider is gated below, so this
        // always-active service resolves it through an ObjectProvider and tolerates its absence (the
        // class-absent case) by serving an empty report. The provider flattens and self-filters the raw
        // mappings (where the predicate string still exists, for byte-identical filtering); the engine
        // service only sorts, queries and pages.
        return new MappingsService(mappingProviders.getIfAvailable());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    BeansService bootUiBeansService(ObjectProvider<BeanProvider> beanProviders) {
        // R2 optional-dependency port: the actuator-typed SpringBeanProvider is gated below, so this
        // always-active service resolves it through an ObjectProvider and tolerates its absence (the
        // class-absent case) by serving an empty list. The provider maps, self-filters and classifies the
        // raw beans (where the live Class and the Spring-specific FRAMEWORK prefix matter, for
        // byte-identical behavior); the engine service only sorts, filters and pages.
        return new BeansService(beanProviders.getIfAvailable());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ConfigService bootUiConfigService(ConfigurableEnvironment environment, BootUiExposure exposure) {
        // The engine ConfigService owns masking, sorting, filtering, paging and profile grouping; the
        // SpringConfigProvider only enumerates the live environment and supplies suggestion metadata (read
        // via Jackson, which the engine must not touch). Built inline like MemoryReportProvider.
        SpringConfigProvider provider = new SpringConfigProvider(
                environment, new ConfigMetadataCatalog(BootUiEngineConfiguration.class.getClassLoader()));
        return new ConfigService(provider, exposure);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    EmailCaptureService bootUiEmailCaptureService(BootUiProperties properties, BootUiExposure exposure) {
        BootUiProperties.Email emailProperties = properties.getEmail();
        return new EmailCaptureService(
                new EmailStore(emailProperties.getMaxEntries()), exposure, emailProperties.isDevTrap());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ScheduledTasksService bootUiScheduledTasksService(ObjectProvider<ScheduledTaskProvider> scheduledTaskProviders) {
        // R2 optional-dependency port: the scheduling-typed SpringScheduledTaskProvider is gated below, so this
        // always-active service resolves it through an ObjectProvider and tolerates its absence (the
        // class-absent case) by serving an empty report. The provider maps and self-filters the raw tasks
        // (where the framework trigger types and the runnable description still exist, for byte-identical
        // filtering); the engine service only sorts and wraps.
        return new ScheduledTasksService(scheduledTaskProviders.getIfAvailable());
    }

    /**
     * R2 optional-dependency port: the Hibernate advisor scanner is only wired when JPA + Hibernate are
     * on the classpath. The JPA-typed factory parameters live in this nested, {@code @ConditionalOnClass}-
     * gated configuration (never inline in the always-active root config), so their types are never linked
     * in a JPA-absent application.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"})
    static class HibernateAdvisorConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        HibernateScanner bootUiHibernateScanner(
                ObjectProvider<EntityManagerFactory> entityManagerFactories,
                ObjectProvider<ListableBeanFactory> beanFactories,
                Environment environment) {
            // Entity discovery (jakarta metamodel via the engine JpaMetamodelReader) + Spring-Data repository
            // discovery live in the adapter; the engine scanner reads config through a neutral property-lookup
            // + active-profiles seam and runs the metamodel walk only on demand (POST /scan).
            return HibernateScanner.using(
                    () -> SpringHibernateDiscovery.discover(entityManagerFactories, beanFactories),
                    environment::getProperty,
                    () -> List.of(environment.getActiveProfiles()),
                    Clock.systemUTC());
        }
    }

    /**
     * R2 optional-dependency port: the Actuator-backed logger provider is only wired when the Actuator
     * loggers endpoint type is on the classpath. The {@code LoggersEndpoint}-typed parameter lives in this
     * nested, {@code @ConditionalOnClass}-gated configuration (never inline in the always-active root
     * config), so the type is never linked in an Actuator-absent application.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.logging.LoggersEndpoint")
    static class LoggersBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringLoggerProvider bootUiSpringLoggerProvider(ObjectProvider<LoggersEndpoint> endpoints) {
            // The endpoint bean may be absent (Actuator present but the loggers endpoint disabled); the
            // provider resolves it live so it can report itself unavailable in that case.
            return new SpringLoggerProvider(endpoints::getIfAvailable);
        }
    }

    /**
     * R2 optional-dependency port: the Actuator-backed health provider is only wired when the Actuator
     * {@code HealthEndpoint} type is on the classpath. The {@code HealthEndpoint}-typed parameter lives in
     * this nested, {@code @ConditionalOnClass}-gated configuration (never inline in the always-active root
     * config), so the type is never linked in an Actuator-absent application.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.actuate.endpoint.HealthEndpoint")
    static class HealthBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringHealthProvider bootUiSpringHealthProvider(ObjectProvider<HealthEndpoint> endpoints) {
            // The endpoint bean may be absent (Actuator present but the health endpoint not exposed); the
            // provider resolves it live so it reports the backend unavailable (engine renders DISABLED).
            return new SpringHealthProvider(endpoints::getIfAvailable);
        }
    }

    /**
     * R2 optional-dependency port: the Actuator-backed mappings provider and the raw descriptor
     * passthrough controller are only wired when the Actuator {@code MappingsEndpoint} type is on the
     * classpath. The {@code MappingsEndpoint}-typed parameters live in this nested,
     * {@code @ConditionalOnClass}-gated configuration (never inline in the always-active root config), so
     * the type and the Web MVC mapping descriptor types are never linked in an Actuator-absent
     * application. The neutral {@code MappingsController} ({@code /flat}) stays unconditional and serves
     * an empty report when this backend is absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.web.mappings.MappingsEndpoint")
    static class MappingsBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringMappingProvider bootUiSpringMappingProvider(
                ObjectProvider<MappingsEndpoint> endpoints, BootUiSelfDataFilter selfDataFilter) {
            // The endpoint bean may be absent (Actuator present but the mappings endpoint not exposed);
            // the provider resolves it live so it reports itself unavailable in that case.
            return new SpringMappingProvider(endpoints::getIfAvailable, selfDataFilter);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        ActuatorMappingsController bootUiActuatorMappingsController(ObjectProvider<MappingsEndpoint> endpoints) {
            // The raw GET /bootui/api/mappings descriptor passthrough (not used by the UI). It is the only
            // touch-point for the Actuator MappingsEndpoint on this path, so it is registered here rather
            // than imported unconditionally, keeping the neutral MappingsController actuator-free.
            return new ActuatorMappingsController(endpoints);
        }
    }

    /**
     * R2 optional-dependency port: the Actuator-backed beans provider is only wired when the Actuator
     * {@code BeansEndpoint} type is on the classpath. The {@code BeansEndpoint}-typed parameter lives in
     * this nested, {@code @ConditionalOnClass}-gated configuration (never inline in the always-active root
     * config), so the type and the beans descriptor types are never linked in an Actuator-absent
     * application. The neutral {@code BeansController} stays unconditional and serves an empty list when
     * this backend is absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.beans.BeansEndpoint")
    static class BeansBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringBeanProvider bootUiSpringBeanProvider(
                ObjectProvider<BeansEndpoint> endpoints, BootUiSelfDataFilter selfDataFilter) {
            // The endpoint bean may be absent (Actuator present but the beans endpoint not exposed); the
            // provider resolves it live so it reports itself unavailable in that case.
            return new SpringBeanProvider(endpoints::getIfAvailable, selfDataFilter);
        }
    }

    /**
     * R2 optional-dependency port: the scheduling-backed task provider is only wired when the scheduling
     * infrastructure type ({@code ScheduledTaskHolder}) is on the classpath. The {@code ScheduledTaskHolder}-
     * typed parameter (and the {@code org.springframework.scheduling.config.*} trigger types the provider maps)
     * live in this nested, {@code @ConditionalOnClass}-gated configuration (never inline in the always-active
     * root config), so they are never linked in an application without the scheduling infrastructure. The
     * always-active {@code bootUiScheduledTasksService} resolves this provider through an {@code ObjectProvider}
     * and serves an empty report when it is absent — though the {@code ScheduledController} that exposes the
     * panel is itself {@code @ConditionalOnClass(ScheduledTaskHolder)}, so the endpoint is only registered when
     * this provider can exist (byte-identical to the original controller's bean-presence).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.scheduling.config.ScheduledTaskHolder")
    static class ScheduledTasksBackendConfiguration {

        @Bean
        ScheduledTaskRunStore bootUiScheduledTaskRunStore(BootUiProperties properties) {
            return new ScheduledTaskRunStore(properties.getActivity().getMaxScheduledTaskRuns());
        }

        @Bean
        ScheduledTaskRunObservationHandler bootUiScheduledTaskRunObservationHandler(
                ScheduledTaskRunStore store, BootUiSelfDataFilter selfDataFilter) {
            return new ScheduledTaskRunObservationHandler(store, selfDataFilter);
        }

        @Bean
        BootUiSchedulingConfigurer bootUiSchedulingConfigurer(ScheduledTaskRunObservationHandler handler) {
            return new BootUiSchedulingConfigurer(handler);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringScheduledTaskProvider bootUiSpringScheduledTaskProvider(
                ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders, BootUiSelfDataFilter selfDataFilter) {
            return new SpringScheduledTaskProvider(
                    scheduledTaskHolders.orderedStream().toList(), selfDataFilter);
        }
    }

    /**
     * The Spring Cache panel backend is only wired when the Spring {@code CacheManager} type is on the
     * classpath. The cache-specific parameters live in this nested, {@code @ConditionalOnClass}-gated
     * configuration (never inline in the always-active root config), so the cache types are never linked in
     * a cache-absent application. The engine {@code CacheService} owns the neutral concerns (metric overlay,
     * ordering, clear orchestration); the byte-identical {@code SpringCacheProvider} owns the Spring-specific
     * topology + operation discovery.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cache.CacheManager")
    static class CacheBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        CacheService bootUiCacheService(
                ObjectProvider<ListableBeanFactory> beanFactoryProvider,
                ObjectProvider<CacheOperationSource> cacheOperationSources,
                ObjectProvider<MeterRegistry> meterRegistries,
                BootUiProperties properties,
                BootUiSelfDataFilter selfDataFilter) {
            // The provider reproduces the former SpringCacheService manager/cache/operation discovery
            // byte-for-byte; the engine reads cache meters from the same registry the old service used
            // (unique-or-first) and applies the identical self-filter predicate.
            SpringCacheProvider provider =
                    new SpringCacheProvider(beanFactoryProvider, cacheOperationSources, properties, selfDataFilter);
            return new CacheService(
                    provider,
                    () -> {
                        MeterRegistry unique = meterRegistries.getIfUnique();
                        return unique != null
                                ? unique
                                : meterRegistries.orderedStream().findFirst().orElse(null);
                    },
                    selfDataFilter::shouldIncludeMeter);
        }

        /**
         * Framework-neutral cache-access recorder feeding the Live Activity panel's {@code CACHE} event
         * type. Declared here (rather than per-adapter) so both the servlet {@code BootUiAutoConfiguration}
         * and {@code BootUiReactiveAutoConfiguration} — which both {@code @Import} this class — share the
         * exact same bean and decoration, since {@code CacheManager}/{@code Cache} are the same abstraction
         * under either web stack (see {@code LiveActivityAssembler}'s class Javadoc for why Quarkus has no
         * equivalent).
         */
        @Bean
        CacheActivityRecorder bootUiCacheActivityRecorder(BootUiProperties properties) {
            BootUiProperties.Cache cache = properties.getCache();
            return new CacheActivityRecorder(
                    cache.isActivityCaptureEnabled() && properties.isPanelEnabled(BootUiPanels.CACHE),
                    cache.getActivityMaxEvents());
        }

        @Bean
        static CacheActivityCacheManagerBeanPostProcessor bootUiCacheActivityCacheManagerBeanPostProcessor(
                ObjectProvider<CacheActivityRecorder> recorderProvider,
                ObjectProvider<BootUiSelfDataFilter> selfDataFilterProvider) {
            return new CacheActivityCacheManagerBeanPostProcessor(recorderProvider, selfDataFilterProvider);
        }
    }

    /**
     * The Live Activity Kafka capture backend is framework-neutral (a bounded in-memory recorder plus two
     * Spring-specific post-processors) and is needed by both servlet and reactive stacks, so it is wired
     * here in the shared engine configuration rather than under the servlet-only auto-configuration. The
     * recorder itself stays ungated exactly as before; the two {@code BeanPostProcessor}s keep their
     * method-level {@code @ConditionalOnClass(KafkaTemplate)} guards so a Spring-Kafka-absent application
     * never links those types.
     */
    @Configuration(proxyBeanMethods = false)
    static class KafkaBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        KafkaActivityRecorder bootUiKafkaActivityRecorder(BootUiProperties properties) {
            BootUiProperties.Kafka kafka = properties.getKafka();
            boolean enabled = kafka.isEnabled() && properties.isPanelEnabled(BootUiPanels.ACTIVITY);
            return new KafkaActivityRecorder(
                    enabled, kafka.isCaptureKey(), kafka.getMaxEntries(), kafka.getMaxKeyLength());
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
        static KafkaProducerCaptureBeanPostProcessor bootUiKafkaProducerCaptureBeanPostProcessor(
                ObjectProvider<KafkaActivityRecorder> recorderProvider) {
            return new KafkaProducerCaptureBeanPostProcessor(recorderProvider);
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
        static KafkaConsumerCaptureBeanPostProcessor bootUiKafkaConsumerCaptureBeanPostProcessor(
                ObjectProvider<KafkaActivityRecorder> recorderProvider) {
            return new KafkaConsumerCaptureBeanPostProcessor(recorderProvider);
        }
    }

    /**
     * The Spring Flyway panel backend is only wired when the Flyway {@link Flyway} type is on the classpath.
     * The Flyway-specific wiring lives in this nested, {@code @ConditionalOnClass}-gated configuration (never
     * inline in the always-active root config), so the Flyway types are never linked in a Flyway-absent
     * application. The engine {@code FlywayService} owns the neutral concerns (counting, sorting, totals,
     * action orchestration); the byte-identical {@code SpringFlywayProvider} owns the Spring-specific bean
     * discovery, the {@code migrate}/{@code clean} primitives and the Spring-Modulith module-aware behaviour.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class FlywayBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        FlywayService bootUiFlywayService(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
            return new FlywayService(new SpringFlywayProvider(beanFactoryProvider));
        }
    }

    /**
     * The Liquibase panel backend is only wired when the {@code liquibase.integration.spring.SpringLiquibase}
     * type is on the classpath. The Liquibase-specific parameters live in this nested,
     * {@code @ConditionalOnClass}-gated configuration (never inline in the always-active root config), so the
     * {@code liquibase.*} types are never linked in a Liquibase-absent application. The engine
     * {@code LiquibaseService} owns the neutral concerns (change-set assembly, ordering, update
     * orchestration); the byte-identical {@code SpringLiquibaseProvider} owns the Spring-specific discovery,
     * change-history reading and the update primitive.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "liquibase.integration.spring.SpringLiquibase")
    static class LiquibaseBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        LiquibaseService bootUiLiquibaseService(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
            return new LiquibaseService(new SpringLiquibaseProvider(beanFactoryProvider));
        }
    }

    /**
     * The Database Connection Pools panel backend is only wired when HikariCP is on the classpath. The
     * Hikari-typed discovery lives in {@link SpringConnectionPoolProvider}, constructed only in this nested,
     * {@code @ConditionalOnClass}-gated configuration (never inline in the always-active root config), so the
     * {@code com.zaxxer.hikari} types are never linked in a pool-absent application. The engine
     * {@code ConnectionPoolService} owns the neutral concerns (URL/username masking, sorting, assembly), so the
     * panel's wire output is unchanged after the extraction.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
    static class ConnectionPoolsBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        ConnectionPoolService bootUiConnectionPoolService(
                ObjectProvider<ListableBeanFactory> beanFactoryProvider, BootUiExposure exposure) {
            return new ConnectionPoolService(new SpringConnectionPoolProvider(beanFactoryProvider), exposure);
        }
    }

    /**
     * The Live Activity JDBC persistence option is opt-in but its beans are now unconditional (not
     * behind {@code @ConditionalOnProperty}): with {@code bootui.activity.persistence.enabled} unset or
     * {@code false}, {@link #bootUiActivityStore} still returns a real bean — a {@link
     * SwitchableActivityStore} wrapping a bare {@link io.github.jdubois.bootui.engine.activity.InMemoryActivityStore}
     * — so {@code LiveActivityController} can always inject it directly (no more {@code ObjectProvider})
     * and later hot-switch it to durable persistence via the "Use the existing datasource" panel action
     * (see {@code ActivitySwitchService}) without a restart. No background thread, connection, or extra
     * bean beyond the in-memory store itself is created until persistence is actually enabled — at
     * startup via configuration, or later via that runtime switch.
     *
     * <p>{@link ActivityPersistenceSettings} is exposed as its own small {@code @Bean} (not inlined into
     * the {@link SwitchableActivityStore} bean method, unlike e.g. {@code HeapDumpSettings}) because two
     * independent consumers need to agree on the exact same resolved settings — in particular the same
     * resolved {@code instanceId} — to stay correctly partitioned: the store bean bakes it into the
     * durable store's own query/prune scope, and {@code LiveActivityController} stamps it onto every entry
     * its capture coordinator captures. A shared singleton bean is the simplest way to guarantee both see
     * one, consistently resolved value (in particular, a generated instance id must be computed exactly
     * once per process, not independently by each consumer).</p>
     *
     * <p>The {@link SwitchableActivityStore} bean is not explicitly closed anywhere in this
     * configuration: it exposes a public no-arg {@code close()} that Spring's default
     * inferred-destroy-method behavior detects and invokes automatically when the context shuts down,
     * delegating to whatever the current delegate is — a durable store's flush/prune scheduler is
     * stopped exactly the same way whether persistence was enabled from startup or switched on later.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class ActivityPersistenceBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        ActivityPersistenceSettings bootUiActivityPersistenceSettings(
                BootUiProperties properties, Environment environment) {
            BootUiProperties.ActivityPersistence persistence =
                    properties.getActivity().getPersistence();
            String instanceId = ActivityInstanceIds.resolveOrDefault(
                    persistence.getInstanceId(), environment.getProperty("spring.application.name"));
            ActivityPersistenceSettings.DataSourceMode dataSourceMode =
                    persistence.getDataSourceMode() == BootUiProperties.ActivityPersistence.DataSourceMode.DEDICATED
                            ? ActivityPersistenceSettings.DataSourceMode.DEDICATED
                            : ActivityPersistenceSettings.DataSourceMode.SHARED;
            return new ActivityPersistenceSettings(
                    persistence.isEnabled(),
                    dataSourceMode,
                    persistence.getDedicatedJdbcUrl(),
                    persistence.getDedicatedUsername(),
                    persistence.getDedicatedPassword(),
                    persistence.getDedicatedDriverClassName(),
                    persistence.getTableName(),
                    persistence.getFlushInterval(),
                    persistence.getBufferMaxEntries(),
                    persistence.getRetention(),
                    instanceId,
                    persistence.getCaptureInterval());
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SwitchableActivityStore bootUiActivityStore(
                ActivityPersistenceSettings settings, ObjectProvider<DataSource> dataSourceProvider) {
            return ActivityStoreFactory.create(settings, () -> resolveActivityDataSource(dataSourceProvider));
        }
    }

    /**
     * Resolves the shared {@code DataSource} to reuse for Live Activity persistence, mirroring {@link
     * #resolveRegistry}: a host application may legitimately have more than one {@code DataSource} bean
     * (for example a primary + an audit datasource), so {@code getIfAvailable()} throwing {@link
     * NoUniqueBeanDefinitionException} falls back to the first one from {@code orderedStream()} rather
     * than propagating and failing BootUI's own startup. Public (unlike the package-private
     * {@link #resolveRegistry}) since {@code LiveActivityController} also calls this directly to resolve
     * {@code dataSourceAvailable} and to feed the "Use the existing datasource" switch action.
     */
    public static DataSource resolveActivityDataSource(ObjectProvider<DataSource> dataSourceProvider) {
        try {
            return dataSourceProvider.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ex) {
            return dataSourceProvider.orderedStream().findFirst().orElse(null);
        }
    }
}
