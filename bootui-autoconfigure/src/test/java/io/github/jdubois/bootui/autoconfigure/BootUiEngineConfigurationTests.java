package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

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
