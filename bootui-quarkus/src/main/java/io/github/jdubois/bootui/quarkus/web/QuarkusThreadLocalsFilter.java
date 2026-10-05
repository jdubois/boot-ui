package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import io.vertx.core.Context;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * The BootUI agent's {@code thread-locals} scope of a Quarkus REST request on its worker thread ({@code
 * docs/PLAN-v2.md} §5.16, M5-5f): a blocking resource method runs on a pooled worker, outside the capture filter's
 * scope on the event loop, so its thread locals are snapshot when the request reaches the worker and diffed when its
 * response leaves it. The lowest priority makes its request filter run first, before the application's filters, and
 * its response filter last, after them, so a filter's leak is seen and a filter's cleanup is not reported. Not
 * pre-matching, which still runs on the event loop; on an event loop nothing is scanned. A scope whose response filter
 * never runs, as after an unmapped failure, is dropped by the next scope on that worker. Observes only.
 */
@Provider
@Priority(Integer.MIN_VALUE + 100)
public class QuarkusThreadLocalsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String SCOPE = QuarkusThreadLocalsFilter.class.getName() + ".scope";

    @Override
    public void filter(ContainerRequestContext request) {
        try {
            if (!AgentThreadLocals.bound() || Context.isOnEventLoopThread()) {
                return;
            }
            long token = AgentThreadLocals.open();
            if (token != 0L) {
                request.setProperty(SCOPE, token);
            }
        } catch (RuntimeException | LinkageError ex) {
            // Diagnostics only; the request continues untouched.
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        try {
            if (request.getProperty(SCOPE) instanceof Long token) {
                request.removeProperty(SCOPE);
                AgentThreadLocals.close(token);
            }
        } catch (RuntimeException | LinkageError ex) {
            // Diagnostics only; the response continues untouched.
        }
    }
}
