package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationObservationContext;

/** {@code docs/PLAN-v2.md} §5.1: Spring Security's authentication time is added to the request that owns it. */
class AuthenticationPhaseObservationHandlerTests {

    @Test
    void addsEachAuthenticationIntervalToItsRequest() throws Exception {
        RequestPhases phases = new RequestPhases();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new AuthenticationPhaseObservationHandler(phases));
        phases.begin("0123456789abcdef");

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            Observation.createNotStarted(
                            "spring.security.authentications", AuthenticationObservationContext::new, registry)
                    .observe(() -> sleep(5));
        }
        Observation.createNotStarted("spring.security.authentications", AuthenticationObservationContext::new, registry)
                .observe(() -> sleep(1));

        assertThat(phases.markers("0123456789abcdef").authenticationMicros())
                .as("only the authentication that ran inside the request's scope")
                .isBetween(4_000L, 1_000_000L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
