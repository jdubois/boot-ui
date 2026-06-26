package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires framework-neutral {@code bootui-engine} services into the Spring adapter.
 *
 * <p>Engine services are annotation-free so they can be produced symmetrically by the Quarkus
 * adapter ({@code @Produces}); here they are exposed as Spring beans built from the adapter's own SPI
 * implementations. Each factory injects the adapter's <em>concrete</em> SPI bean (e.g.
 * {@link BootUiExposure}) rather than the SPI interface, so adding more SPI implementations later can
 * never make this wiring ambiguous — the engine service still depends only on the neutral interface.</p>
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
}
