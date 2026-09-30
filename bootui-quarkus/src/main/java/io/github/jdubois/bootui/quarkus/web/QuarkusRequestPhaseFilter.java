package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
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
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        mark(RequestPhase.RESPONSE);
    }

    private void mark(RequestPhase phase) {
        try {
            phases.mark(QuarkusRequestCorrelation.current().requestId(), phase);
        } catch (RuntimeException ex) {
            // Phase markers are diagnostics only; the request continues untouched.
        }
    }
}
