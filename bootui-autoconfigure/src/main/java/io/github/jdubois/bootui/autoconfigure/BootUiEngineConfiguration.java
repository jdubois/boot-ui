package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

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
}
