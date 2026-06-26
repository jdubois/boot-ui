package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.architecture.SpringBasePackageProvider;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
