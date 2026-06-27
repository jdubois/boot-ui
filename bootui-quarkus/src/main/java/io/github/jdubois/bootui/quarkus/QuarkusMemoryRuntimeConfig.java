package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.spi.MemoryRuntimeConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Quarkus implementation of the framework-neutral {@link MemoryRuntimeConfig} consumed by the engine
 * {@code MemoryReportProvider} behind the Live Memory panel.
 *
 * <p>This is the Quarkus analogue of the Spring adapter's {@code SpringMemoryRuntimeConfig}. Where the
 * Spring impl reads {@code spring.threads.virtual.enabled} and the {@code management.endpoint.health.*}
 * chain, Quarkus has no single equivalent for either, so both answers are derived differently here:</p>
 *
 * <ul>
 *   <li><strong>{@link #virtualThreadsEnabled()} is a deliberate constant {@code false}.</strong> Quarkus
 *       has no application-wide virtual-threads switch — virtual threads are opted into per endpoint with
 *       {@code @RunOnVirtualThread} (and {@code quarkus.virtual-threads.enabled} is only an inverted
 *       kill-switch). Reporting {@code false} keeps the per-thread stack sizing on the conservative
 *       platform-thread assumption. The known limitation is that an application that adopts
 *       {@code @RunOnVirtualThread} broadly may be under-sized by this report; that is accepted for now
 *       rather than guessing from an unreliable signal.</li>
 *   <li><strong>{@link #kubernetesHealthProbesEnabled()} reflects SmallRye Health <em>extension
 *       presence</em>,</strong> not a configuration property. SmallRye Health has no global enable flag —
 *       you add or remove the extension — so the honest signal is whether its reporter type is on the
 *       runtime classpath. When the extension is absent (the default for an app that only adds BootUI)
 *       this returns {@code false}, so the generated manifest omits liveness/readiness probe stanzas
 *       rather than advertising endpoints that do not exist.</li>
 * </ul>
 *
 * <p>The Live Memory panel does not render the Kubernetes recommendation, so the probe answer is not
 * surfaced to the user yet; it is kept honest here so the report payload stays correct ahead of the JVM
 * Tuning panel (which does render it) being ported. Both answers are stable for the lifetime of the
 * application, so they are resolved once rather than per call.</p>
 */
@ApplicationScoped
public class QuarkusMemoryRuntimeConfig implements MemoryRuntimeConfig {

    private static final String SMALLRYE_HEALTH_REPORTER = "io.smallrye.health.SmallRyeHealthReporter";

    private static final boolean SMALLRYE_HEALTH_PRESENT = isSmallRyeHealthPresent();

    @Override
    public boolean virtualThreadsEnabled() {
        return false;
    }

    @Override
    public boolean kubernetesHealthProbesEnabled() {
        return SMALLRYE_HEALTH_PRESENT;
    }

    private static boolean isSmallRyeHealthPresent() {
        try {
            Class.forName(SMALLRYE_HEALTH_REPORTER, false, QuarkusMemoryRuntimeConfig.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
