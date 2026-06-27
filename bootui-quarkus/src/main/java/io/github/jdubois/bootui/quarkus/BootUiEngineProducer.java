package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
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
}
