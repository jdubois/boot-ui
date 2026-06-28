package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.quarkus.logging.QuarkusLoggerProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
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
}
