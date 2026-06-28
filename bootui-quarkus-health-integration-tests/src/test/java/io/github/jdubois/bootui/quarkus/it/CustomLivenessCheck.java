package io.github.jdubois.bootui.quarkus.it;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * A custom MicroProfile {@code @Liveness} health check used by {@link BootUiQuarkusHealthCaptureTest} to prove
 * that an application-authored SmallRye check surfaces on the BootUI Health panel with a known name, status and
 * data. As a {@code @QuarkusTest} test-scoped bean it is discovered by SmallRye Health and aggregated into the
 * report {@code SmallRyeHealthReporter#getHealth()} returns.
 */
@Liveness
@ApplicationScoped
public class CustomLivenessCheck implements HealthCheck {

    static final String CHECK_NAME = "bootui-it-liveness";

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named(CHECK_NAME)
                .up()
                .withData("detail", "alive")
                .build();
    }
}
