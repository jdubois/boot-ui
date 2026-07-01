package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.quarkus.health.QuarkusHealthProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * SmallRye Health capture wiring for the Quarkus adapter: produces the {@link HealthProvider} the engine
 * {@code HealthService} reads, backed by the in-process {@link SmallRyeHealthReporter}.
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when SmallRye Health is absent.</strong> The extension runtime jar is Jandex-indexed (so Arc
 * discovers the always-on beans), and Arc treats a {@code @Produces} method as bean-defining — so this producer
 * is discovered whenever the jar is indexed, and Arc would try to resolve its {@link SmallRyeHealthReporter}
 * <em>parameter</em> type even in an application without {@code quarkus-smallrye-health}, linking a type that
 * must stay absent (R2). The processor therefore actively excludes this class from discovery unless the
 * SmallRye-Health capability is present (see {@code BootUiQuarkusProcessor#registerSmallRyeHealthCapture}).
 * This mirrors {@link BootUiOtelProducer} exactly.</p>
 *
 * <p>When SmallRye Health is absent there is no {@code HealthProvider} bean, so the engine
 * {@code HealthService} (produced unconditionally by {@link BootUiEngineProducer}) is given a {@code null}
 * provider and renders the DISABLED root with {@code QuarkusHealthGuidance}'s setup steps.</p>
 */
public class BootUiHealthProducer {

    @Produces
    @Singleton
    public HealthProvider bootUiHealthProvider(SmallRyeHealthReporter reporter) {
        return new QuarkusHealthProvider(reporter);
    }
}
