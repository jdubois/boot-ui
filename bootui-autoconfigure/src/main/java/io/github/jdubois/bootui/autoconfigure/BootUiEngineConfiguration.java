package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.SpringBasePackageProvider;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.SpringMemoryRuntimeConfig;
import io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateDiscovery;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

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
}
