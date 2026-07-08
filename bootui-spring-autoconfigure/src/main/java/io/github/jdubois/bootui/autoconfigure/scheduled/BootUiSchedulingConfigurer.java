package io.github.jdubois.bootui.autoconfigure.scheduled;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Installs BootUI's {@link ScheduledTaskRunObservationHandler} onto whatever {@link ObservationRegistry}
 * ends up wired into the host application's {@link ScheduledTaskRegistrar}, without disturbing any
 * observability the host application already configures for its own scheduling metrics/tracing.
 *
 * <p>Runs at {@link Ordered#LOWEST_PRECEDENCE} — deliberately last among any {@code
 * SchedulingConfigurer} beans (Spring sorts and invokes them all during context refresh; see {@code
 * ScheduledAnnotationBeanPostProcessor.finishRegistration}) — so by the time it runs, the registrar's
 * {@link ScheduledTaskRegistrar#getObservationRegistry() observation registry} already reflects
 * whatever the host application configured (or {@code null} if it configured none). Either way this
 * configurer never replaces an existing registry — losing the host's own handlers — it only adds its
 * handler to it; when none exists yet, it creates a dedicated one purely for BootUI's own capture.</p>
 */
public final class BootUiSchedulingConfigurer implements SchedulingConfigurer, Ordered {

    private final ObservationHandler<?> handler;

    public BootUiSchedulingConfigurer(ObservationHandler<?> handler) {
        this.handler = handler;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ObservationRegistry registry = registrar.getObservationRegistry();
        if (registry == null) {
            registry = ObservationRegistry.create();
            registrar.setObservationRegistry(registry);
        }
        registry.observationConfig().observationHandler(handler);
    }
}
