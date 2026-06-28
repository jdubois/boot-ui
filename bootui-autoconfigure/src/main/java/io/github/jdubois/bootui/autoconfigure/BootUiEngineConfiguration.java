package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.SpringBasePackageProvider;
import io.github.jdubois.bootui.autoconfigure.beans.SpringBeanProvider;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.SpringMemoryRuntimeConfig;
import io.github.jdubois.bootui.autoconfigure.crac.CracRuntimeInventoryCollector;
import io.github.jdubois.bootui.autoconfigure.graalvm.HttpReachabilityMetadataRepository;
import io.github.jdubois.bootui.autoconfigure.health.SpringHealthGuidance;
import io.github.jdubois.bootui.autoconfigure.health.SpringHealthProvider;
import io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateDiscovery;
import io.github.jdubois.bootui.autoconfigure.logging.SpringLoggerProvider;
import io.github.jdubois.bootui.autoconfigure.mappings.SpringMappingProvider;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.pentesting.SpringPentestingObservationCollector;
import io.github.jdubois.bootui.autoconfigure.web.ActuatorMappingsController;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner;
import io.github.jdubois.bootui.engine.graalvm.GraalVmDependencySettings;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.pentesting.PentestingScanner;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
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
        // springdoc/OpenAPI presence is probed live, and the ArchUnit import runs only on demand (POST /scan).
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
        SpringPentestingObservationCollector collector = new SpringPentestingObservationCollector(
                applicationContext,
                applicationContext.getBeanProvider(RequestMappingInfoHandlerMapping.class),
                environment,
                properties);
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
}
