package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.github.jdubois.bootui.quarkus.exceptions.QuarkusResourceHandlers;
import io.vertx.core.Context;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Marks when a Quarkus REST request enters its resource method, and when the method has returned and the response
 * entity is about to be written ({@code docs/PLAN-v2.md} §5.1). Its high priority makes its request filter run last,
 * right before the method, and its response filter run first, right after it; JAX-RS writes the entity after every
 * response filter, so serialization, where lazy loading surfaces, runs in the {@code RESPONSE} phase. Observes only.
 *
 * <p>On a worker or virtual thread, where no correlation scope runs because Quarkus keeps the request's context on its
 * duplicated Vert.x context, the request filter also opens the request's segment and registers its close on Quarkus'
 * request-completion callback ({@code docs/PLAN-v2.md} §5.11). An ordinary response is written by that same thread,
 * so the capture filter's take already closes the segment there; the completion close releases the thread of a
 * response whose body outlives its chain. See {@link QuarkusRequestSegments}.</p>
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
        QuarkusRequestSegments.enter(request);
        assemblyOnlyUnlessBlocking();
    }

    /**
     * The types whose resource methods only assemble their result: Mutiny's, a {@code CompletionStage}, and Reactive
     * Streams' and the JDK's publishers ({@code docs/PLAN-v2.md} §5.14, M5-4b). Compared by name, so none is loaded.
     */
    static final Set<String> ASYNCHRONOUS_RESULTS = Set.of(
            "io.smallrye.mutiny.Uni",
            "io.smallrye.mutiny.Multi",
            "java.util.concurrent.CompletionStage",
            "org.reactivestreams.Publisher",
            "java.util.concurrent.Flow$Publisher");

    /**
     * Marks the request assembly only for the BootUI agent's code paths unless its resource method runs blocking on a
     * worker and returns its result: on the event loop, or returning an asynchronous result, the method's tree times
     * only the assembly, and when the method is unknown BootUI says so rather than guess.
     */
    private static void assemblyOnlyUnlessBlocking() {
        try {
            if (!AgentCodePaths.bound()) {
                return;
            }
            String requestId = QuarkusRequestCorrelation.current().requestId();
            if (requestId == null) {
                return;
            }
            Method method = QuarkusResourceHandlers.currentResourceMethod();
            if (Context.isOnEventLoopThread() || method == null || asynchronous(method.getReturnType())) {
                AgentCodePaths.assemblyOnly(requestId);
            }
        } catch (RuntimeException | LinkageError ex) {
            // Code paths are diagnostics only; the request continues untouched.
        }
    }

    /**
     * Whether each return type only assembles its result, walked once per class: a resource method's return type is
     * looked up per request, never walked again. A {@link ClassValue}, so a class of a reloaded application is never
     * kept alive by it.
     */
    private static final ClassValue<Boolean> ASYNCHRONOUS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            WALKS.incrementAndGet();
            return walk(type);
        }
    };

    /** Tests only: how many return types were walked. */
    static final AtomicLong WALKS = new AtomicLong();

    /** Whether {@code type} is, extends, or implements one of {@link #ASYNCHRONOUS_RESULTS}; cached per class. */
    static boolean asynchronous(Class<?> type) {
        return type != null && ASYNCHRONOUS.get(type);
    }

    private static boolean walk(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (ASYNCHRONOUS_RESULTS.contains(type.getName())) {
            return true;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (walk(implemented)) {
                return true;
            }
        }
        return walk(type.getSuperclass());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        mark(RequestPhase.RESPONSE);
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
