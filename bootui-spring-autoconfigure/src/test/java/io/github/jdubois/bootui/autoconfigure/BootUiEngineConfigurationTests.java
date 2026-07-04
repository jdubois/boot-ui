package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.architecture.SpringBasePackageProvider;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.activity.ActivityForwardingSettings;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins the property-to-record mappings in {@link BootUiEngineConfiguration}.
 *
 * <p>Each engine-bean factory builds an immutable, framework-neutral input from {@link BootUiProperties}
 * by positional construction out of same-typed getters (several {@code boolean}s, several {@code int}s).
 * A silent field transposition there would compile, pass the engine's own tests (which construct the
 * record directly), and only surface as a wrong value in production. So every factory pins its mapping
 * here with distinct non-default values for adjacent same-typed fields. This test lives in the adapter
 * package so it can call the package-private factory methods directly.</p>
 */
class BootUiEngineConfigurationTests {

    @Test
    void heapDumpServiceMapsHeapDumpPropertiesWithoutTransposition() {
        BootUiProperties properties = new BootUiProperties();
        BootUiProperties.HeapDump heapDump = properties.getHeapDump();
        heapDump.setOutputDir("custom-heap-dir");
        heapDump.setCaptureEnabled(false); // distinct from allowRawDownload to catch a boolean swap
        heapDump.setAllowRawDownload(true); // default is false
        heapDump.setMaxDumps(7); // default is 5

        HeapDumpService service = new BootUiEngineConfiguration().bootUiHeapDumpService(properties);
        HeapDumpReport report = service.report();

        assertThat(report.outputDirectory()).endsWith("custom-heap-dir");
        assertThat(report.captureEnabled()).isFalse();
        assertThat(report.rawDownloadEnabled()).isTrue();
        assertThat(service.rawDownloadAllowed()).isTrue();
        assertThat(report.maxDumps()).isEqualTo(7);
        // maxClasses / topClasses semantics are pinned by HeapDumpServiceTests in bootui-engine.
    }

    @Test
    void basePackageProviderFactoryProducesSpringProvider() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            BasePackageProvider provider = new BootUiEngineConfiguration().bootUiBasePackageProvider(context);

            assertThat(provider).isInstanceOf(SpringBasePackageProvider.class);
        }
    }

    @Test
    void architectureScannerFactoryWiresBasePackageProviderIntoTheScanner() {
        // Pins the base-package seam: the scanner must read its base packages from the injected provider
        // (the supplier is what bounds the on-demand ArchUnit import to the host application's own code).
        ArchitectureScanner scanner =
                new BootUiEngineConfiguration().bootUiArchitectureScanner(() -> List.of("com.example.wiring"));

        ArchitectureReport initial = scanner.initialReport();

        assertThat(initial.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(initial.basePackages()).containsExactly("com.example.wiring");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hibernateScannerFactoryWiresPropertyLookupAndActiveProfilesSeam() {
        // Pins the R2 config seam: the nested HibernateAdvisorConfiguration must read host config through a
        // neutral property-lookup + active-profiles seam derived from the Environment. We feed a distinctive
        // property (ddl-auto=update) AND a distinctive active profile (prod); the ddl-auto rule only escalates
        // to a "production-like profile" violation when BOTH the property value and the profile flow through.
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        environment.setActiveProfiles("prod");
        ObjectProvider<EntityManagerFactory> entityManagerFactories = mock(ObjectProvider.class);
        when(entityManagerFactories.stream()).thenReturn(Stream.of(stubFactoryWithOneEntity()));
        ObjectProvider<ListableBeanFactory> beanFactories = mock(ObjectProvider.class);
        when(beanFactories.getIfAvailable()).thenReturn(null);

        HibernateScanner scanner = new BootUiEngineConfiguration.HibernateAdvisorConfiguration()
                .bootUiHibernateScanner(entityManagerFactories, beanFactories, environment);
        HibernateReport report = scanner.scan();

        HibernateRuleResultDto ddlAuto = report.results().stream()
                .filter(result -> result.id().equals("HIB-CONFIG-002"))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("ddl-auto rule did not surface; property/profile seam not wired"));
        assertThat(ddlAuto.sampleViolations()).anyMatch(detail -> detail.contains("production-like profile"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loggersServiceFactoryWiresReadVisibilityAndWriteGuardSeparately() {
        // Pins the B3 read/write split: both seams are Predicate<String>, so a transposition would compile.
        // The read predicate must be shouldIncludeLogger (hides BootUI's own loggers from the panel); the
        // write predicate must be isBootUiLoggerName (blocks mutating BootUI's loggers regardless of the
        // read preference). Swapping them would expose BootUI's loggers and/or block writes to ordinary
        // application loggers.
        String bootUiLogger = "io.github.jdubois.bootui.autoconfigure.web.LoggersController";
        String appLogger = "com.example.OrderService";
        RecordingLoggerProvider provider = new RecordingLoggerProvider(
                List.of(new LoggerDto(bootUiLogger, "DEBUG", "DEBUG"), new LoggerDto(appLogger, "INFO", "INFO")));
        ObjectProvider<LoggerProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);

        LoggersService service =
                new BootUiEngineConfiguration().bootUiLoggersService(providers, BootUiSelfDataFilter.defaults());

        // Read predicate = shouldIncludeLogger: BootUI's own logger is hidden, the app logger is shown.
        assertThat(service.report(null, null, null).loggers())
                .extracting(LoggerDto::name)
                .containsExactly(appLogger);

        // Write predicate = isBootUiLoggerName: the app logger is writable, BootUI's own logger is rejected
        // before the provider is touched.
        service.setLevel(appLogger, "WARN");
        assertThat(provider.lastSetName).isEqualTo(appLogger);
        assertThatThrownBy(() -> service.setLevel(bootUiLogger, "WARN")).isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.lastSetName).isEqualTo(appLogger);
    }

    @Test
    void healthServiceFactoryWiresTheSpringGuidanceAndResolvesTheProvider() {
        // With a provider that returns a tree of only default Spring contributors, the factory must wire
        // SpringHealthGuidance.INSTANCE so the engine recognizes the default-name set and emits Spring's
        // default-contributor guidance copy. Proves the platform-specific defaults/copy reach the engine.
        HealthNodeDto onlyDefaults = new HealthNodeDto(
                "application",
                "UP",
                null,
                List.of(
                        new HealthNodeDto("livenessState", "UP", null, List.of()),
                        new HealthNodeDto("readinessState", "UP", null, List.of())));
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(() -> onlyDefaults);

        HealthService service = new BootUiEngineConfiguration().bootUiHealthService(providers);
        HealthNodeDto node = service.health();

        assertThat(node.status()).isEqualTo("UP");
        assertThat(node.available()).isTrue();
        assertThat(node.guidanceReason()).isEqualTo("Only Spring Boot default health indicators are available");
        assertThat(node.setup().get(0).title()).isEqualTo("Add application health contributors");
    }

    @Test
    void healthServiceFactoryRendersDisabledRootWhenNoProviderIsPresent() {
        // No SpringHealthProvider bean (the Actuator-absent class-gated case): the factory must still build
        // a service from SpringHealthGuidance.INSTANCE that renders the DISABLED root with Spring's reason.
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(null);

        HealthNodeDto node =
                new BootUiEngineConfiguration().bootUiHealthService(providers).health();

        assertThat(node.status()).isEqualTo("DISABLED");
        assertThat(node.available()).isFalse();
        assertThat(node.unavailableReason()).isEqualTo("Spring Boot Actuator health endpoint is not available");
        assertThat(node.setup().get(0).title()).isEqualTo("Add Spring Boot Actuator");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mappingsServiceFactoryResolvesTheProviderAndSortsThePagedReport() {
        // The factory wires the (optional) MappingProvider into the engine MappingsService via an
        // ObjectProvider. A provider returning unsorted mappings must come back sorted by pattern, proving
        // the resolved provider is actually fed to the service rather than dropped.
        MappingProvider provider = new MappingProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<MappingDto> mappings() {
                return List.of(
                        new MappingDto("GET", "/zebra", "Z#z", null, null),
                        new MappingDto("GET", "/alpha", "A#a", null, null));
            }
        };
        ObjectProvider<MappingProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);

        MappingsReport report =
                new BootUiEngineConfiguration().bootUiMappingsService(providers).report(null, null, null);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.mappings()).extracting(MappingDto::pattern).containsExactly("/alpha", "/zebra");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mappingsServiceFactoryServesEmptyReportWhenNoProviderIsPresent() {
        // The Actuator-absent class-gated case: no MappingProvider bean. The always-active factory must
        // still build a service that reports an empty (available=false) result rather than failing.
        ObjectProvider<MappingProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(null);

        MappingsReport report =
                new BootUiEngineConfiguration().bootUiMappingsService(providers).report(null, null, null);

        assertThat(report.total()).isZero();
        assertThat(report.mappings()).isEmpty();
    }

    @Test
    void activityPersistenceSettingsFactoryMapsPropertiesWithoutTransposition() {
        // Several adjacent fields share a type (four Durations, several Strings), so a positional
        // transposition in the factory would compile and only surface as a wrong value at runtime. Every
        // field below is set to a value distinct from both the default and its same-typed neighbors.
        BootUiProperties properties = new BootUiProperties();
        BootUiProperties.ActivityPersistence persistence =
                properties.getActivity().getPersistence();
        persistence.setEnabled(true);
        persistence.setDataSourceMode(BootUiProperties.ActivityPersistence.DataSourceMode.DEDICATED);
        persistence.setDedicatedJdbcUrl("jdbc:h2:mem:pinned");
        persistence.setDedicatedUsername("pinned-user");
        persistence.setDedicatedPassword("pinned-pass");
        persistence.setDedicatedDriverClassName("org.h2.Driver");
        persistence.setTableName("pinned_table");
        persistence.setFlushInterval(Duration.ofSeconds(11));
        persistence.setBufferMaxEntries(321);
        persistence.setRetention(Duration.ofDays(3));
        persistence.setInstanceId("pinned-instance");
        persistence.setCaptureInterval(Duration.ofSeconds(7));
        // instanceId is already configured (non-blank), so the Environment is not consulted for it; the
        // HOSTNAME-env-var / generated-id fallback paths are pinned separately by ActivityInstanceIdsTests.
        MockEnvironment environment = new MockEnvironment();

        ActivityPersistenceSettings settings = new BootUiEngineConfiguration.ActivityPersistenceBackendConfiguration()
                .bootUiActivityPersistenceSettings(properties, environment);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.DEDICATED);
        assertThat(settings.dedicatedJdbcUrl()).isEqualTo("jdbc:h2:mem:pinned");
        assertThat(settings.dedicatedUsername()).isEqualTo("pinned-user");
        assertThat(settings.dedicatedPassword()).isEqualTo("pinned-pass");
        assertThat(settings.dedicatedDriverClassName()).isEqualTo("org.h2.Driver");
        assertThat(settings.tableName()).isEqualTo("pinned_table");
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(11));
        assertThat(settings.bufferMaxEntries()).isEqualTo(321);
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(3));
        assertThat(settings.instanceId()).isEqualTo("pinned-instance");
        assertThat(settings.captureInterval()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void activityForwardingSettingsFactoryMapsPropertiesWithoutTransposition() {
        // Mirrors activityPersistenceSettingsFactoryMapsPropertiesWithoutTransposition: several adjacent
        // fields share a type (four Durations, several Strings), so a positional transposition in the
        // factory would compile and only surface as a wrong value at runtime. Every field below is set
        // to a value distinct from both the default and its same-typed neighbors.
        BootUiProperties properties = new BootUiProperties();
        BootUiProperties.ActivityForwarding forwarding =
                properties.getActivity().getForwarding();
        forwarding.setEnabled(true);
        forwarding.setPeerBaseUrl("http://peer.example:9090");
        forwarding.setSharedSecret("pinned-secret");
        forwarding.setConnectTimeout(Duration.ofSeconds(13));
        forwarding.setRequestTimeout(Duration.ofSeconds(17));
        forwarding.setFlushInterval(Duration.ofSeconds(19));
        forwarding.setBufferMaxEntries(654);
        forwarding.setInstanceId("pinned-forwarding-instance");
        forwarding.setCaptureInterval(Duration.ofSeconds(23));
        // instanceId is already configured (non-blank), so the Environment is not consulted for it; the
        // HOSTNAME-env-var / generated-id fallback paths are pinned separately by ActivityInstanceIdsTests.
        MockEnvironment environment = new MockEnvironment();

        ActivityForwardingSettings settings = new BootUiEngineConfiguration.ActivityPersistenceBackendConfiguration()
                .bootUiActivityForwardingSettings(properties, environment);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.peerBaseUrl()).isEqualTo("http://peer.example:9090");
        assertThat(settings.sharedSecret()).isEqualTo("pinned-secret");
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(13));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(17));
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(19));
        assertThat(settings.bufferMaxEntries()).isEqualTo(654);
        assertThat(settings.instanceId()).isEqualTo("pinned-forwarding-instance");
        assertThat(settings.captureInterval()).isEqualTo(Duration.ofSeconds(23));
    }

    @Test
    void activityStoreFactoryResolvesAvailableDataSource() {
        // Mirrors resolveRegistryReturnsAvailableRegistry: pins that the ActivityStore factory's own
        // DataSource-resolution helper (not ActivityStoreFactory.create itself, already fully tested in
        // bootui-engine) simply returns the available bean in the common, unambiguous case.
        DataSource dataSource = mock(DataSource.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> dataSourceProvider = mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);

        assertThat(BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider))
                .isSameAs(dataSource);
    }

    @Test
    @SuppressWarnings("unchecked")
    void activityStoreFactoryResolvesDataSourceEvenWhenAmbiguous() {
        // Mirrors resolveRegistryFallsBackToFirstWhenAmbiguous: a host application may have more than one
        // DataSource bean (e.g. a routing/primary + audit datasource); getIfAvailable() throws in that case,
        // so the factory must fall back to the first one from orderedStream() rather than propagating and
        // failing BootUI's own startup.
        DataSource first = mock(DataSource.class);
        DataSource second = mock(DataSource.class);
        ObjectProvider<DataSource> dataSourceProvider = mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable())
                .thenThrow(new NoUniqueBeanDefinitionException(DataSource.class, 2, "two datasources"));
        when(dataSourceProvider.orderedStream()).thenReturn(Stream.of(first, second));

        assertThat(BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider))
                .isSameAs(first);
    }

    private static final class RecordingLoggerProvider implements LoggerProvider {

        private final List<LoggerDto> loggers;

        private String lastSetName;

        RecordingLoggerProvider(List<LoggerDto> loggers) {
            this.loggers = loggers;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public LoggersReport rawLoggers() {
            return new LoggersReport(List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"), loggers);
        }

        @Override
        public LoggerDto setLevel(String name, String level) {
            this.lastSetName = name;
            return new LoggerDto(name, level, level);
        }
    }

    /**
     * Minimal {@link EntityManagerFactory} backed by JDK proxies that exposes a single mapped entity, so
     * the scanner does not short-circuit on an empty metamodel and the config rules (which read the
     * property-lookup + active-profiles seam) actually evaluate. Mockito is avoided for the metamodel
     * types because they are generic interfaces; hand-rolled proxies keep the fixture dependency-free.
     */
    private static EntityManagerFactory stubFactoryWithOneEntity() {
        EntityType<?> entityType = (EntityType<?>) Proxy.newProxyInstance(
                BootUiEngineConfigurationTests.class.getClassLoader(),
                new Class<?>[] {EntityType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> SampleEntity.class;
                    case "getName" -> "SampleEntity";
                    case "getAttributes" -> Set.of();
                    case "toString" -> "StubEntityType";
                    default -> defaultValue(method);
                });
        Metamodel metamodel = (Metamodel) Proxy.newProxyInstance(
                BootUiEngineConfigurationTests.class.getClassLoader(),
                new Class<?>[] {Metamodel.class},
                (proxy, method, args) ->
                        "getEntities".equals(method.getName()) ? Set.of(entityType) : defaultValue(method));
        return (EntityManagerFactory) Proxy.newProxyInstance(
                BootUiEngineConfigurationTests.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> "getMetamodel".equals(method.getName()) ? metamodel : defaultValue(method));
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        return returnType == boolean.class ? Boolean.FALSE : null;
    }

    private static final class SampleEntity {}

    @Test
    @SuppressWarnings("unchecked")
    void resolveRegistryReturnsAvailableRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
        when(registries.getIfAvailable()).thenReturn(registry);

        assertThat(BootUiEngineConfiguration.resolveRegistry(registries)).isSameAs(registry);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRegistryFallsBackToFirstWhenAmbiguous() {
        SimpleMeterRegistry first = new SimpleMeterRegistry();
        SimpleMeterRegistry second = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
        when(registries.getIfAvailable()).thenThrow(new NoUniqueBeanDefinitionException(MeterRegistry.class, 2, "two"));
        when(registries.orderedStream()).thenReturn(Stream.of(first, second));

        assertThat(BootUiEngineConfiguration.resolveRegistry(registries)).isSameAs(first);
    }
}
