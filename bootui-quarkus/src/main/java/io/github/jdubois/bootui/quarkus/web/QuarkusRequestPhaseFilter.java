package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.vertx.core.Context;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Marks when a Quarkus REST request enters its resource method, and when the method has returned and the response
 * entity is about to be written ({@code docs/PLAN-v2.md} §5.1). Its high priority makes its request filter run last,
 * right before the method, and its response filter run first, right after it; JAX-RS writes the entity after every
 * response filter, so serialization, where lazy loading surfaces, runs in the {@code RESPONSE} phase. Observes only.
 *
 * <p>On a worker or virtual thread, where no correlation scope runs because Quarkus keeps the request's context on its
 * duplicated Vert.x context, the request filter also opens the request's segment ({@code docs/PLAN-v2.md} §5.11). It
 * stays open through serialization, until the thread switches to other correlated work or the HTTP capture filter
 * takes the request's usage when the response has been written.</p>
 */
@Provider
@Priority(Integer.MAX_VALUE - 100)
public class QuarkusRequestPhaseFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private final RequestPhases phases;

    @Inject
    public QuarkusRequestPhaseFilter(RequestPhases phases) {
        this.phases = phases;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        mark(RequestPhase.HANDLER);
        enterSegment();
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        mark(RequestPhase.RESPONSE);
    }

    private static void enterSegment() {
        try {
            if (BootUiCorrelation.current().isEmpty() && !Context.isOnEventLoopThread()) {
                SegmentMeter.shared()
                        .switchTo(QuarkusRequestCorrelation.current().requestId());
            }
        } catch (RuntimeException | LinkageError ex) {
            // Measuring never disturbs the request.
        }
    }

    private void mark(RequestPhase phase) {
        // Where the BootUI agent's code-paths nodes record the phase they entered in (docs/PLAN-v2.md M5-4a), on the
        // thread that runs the resource method.
        AgentCodePaths.phase(phase);
        try {
            phases.mark(QuarkusRequestCorrelation.current().requestId(), phase);
        } catch (RuntimeException ex) {
            // Phase markers are diagnostics only; the request continues untouched.
        }
    }
}
