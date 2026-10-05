package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.security.authentication.AuthenticationObservationContext;

/**
 * Adds the time Spring Security spends authenticating a request to that request's phase markers
 * ({@code docs/PLAN-v2.md} §5.1), from the authentication observation Spring Security emits when an observation
 * registry is present. The request is the one whose BootUI scope is current when authentication starts. Observes only.
 */
public final class AuthenticationPhaseObservationHandler
        implements ObservationHandler<AuthenticationObservationContext> {

    private final RequestPhases phases;

    public AuthenticationPhaseObservationHandler(RequestPhases phases) {
        this.phases = phases;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof AuthenticationObservationContext;
    }

    @Override
    public void onStart(AuthenticationObservationContext context) {
        String requestId = BootUiCorrelation.current().requestId();
        if (requestId != null) {
            context.put(Started.class, new Started(requestId, System.nanoTime()));
        }
    }

    @Override
    public void onStop(AuthenticationObservationContext context) {
        Started started = context.get(Started.class);
        if (started != null) {
            phases.addAuthentication(started.requestId(), (System.nanoTime() - started.nanos()) / 1_000L);
        }
    }

    private record Started(String requestId, long nanos) {}
}
