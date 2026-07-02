package io.github.jdubois.bootui.quarkus.exceptions;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.reflect.Method;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;

/**
 * Resolves a best-effort {@code "ResourceClass#method"} description of the JAX-RS resource handling the
 * request in flight — the Quarkus analogue of the Spring adapter's {@code
 * BootUiExceptionHandlerResolver#describeHandler}, which renders Spring MVC's {@code HandlerMethod} the same
 * way.
 *
 * <p>Quarkus has no {@code HandlerExceptionResolver} equivalent, so both Quarkus exception feeders
 * ({@code QuarkusExceptionLogHandler} and {@code QuarkusExceptionCaptureFilter}) resolve the handler
 * themselves via {@link CurrentRequestManager#get()} — RESTEasy Reactive's own current-request accessor,
 * populated/cleared in lockstep with the CDI request scope (set at {@code
 * ResteasyReactiveRequestContext#handleRequestScopeActivation()}, cleared at {@code
 * #requestScopeDeactivated()}), the same lifecycle the {@code CurrentVertxRequest} bean those feeders already
 * read method/path from follows. It is not a RESTEasy Reactive implementation detail invented for this
 * purpose: the first-party {@code quarkus-rest-jackson} extension calls this exact method directly to
 * resolve per-method {@code @JsonView}s, and {@link ResteasyReactiveRequestContext#getResteasyReactiveResourceInfo()}
 * is the same lookup RESTEasy Reactive uses internally to back standard {@code @Context ResourceInfo}
 * injection in application code — so this is a supported, if lightly-documented, extension point rather than
 * a fragile internal.</p>
 *
 * <p>Every path is guarded and silent: with no active request scope (a background/scheduled failure), no
 * resource matched yet (e.g. a 404 or a filter-stage failure before routing), or any failure, the result is
 * simply {@code null} — identical in spirit to how method/path already degrade on Quarkus.</p>
 */
public final class QuarkusResourceHandlers {

    private QuarkusResourceHandlers() {}

    /**
     * The current request's resource class + method, or {@code null} when unavailable for any reason.
     */
    public static String currentHandler() {
        try {
            ResteasyReactiveRequestContext current = CurrentRequestManager.get();
            return current == null ? null : describe(current.getResteasyReactiveResourceInfo());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Renders a JAX-RS {@link ResourceInfo} as {@code "ResourceClass#method"}, matching the Spring adapter's
     * {@code HandlerMethod} format. Package-visible for direct unit testing without needing a live RESTEasy
     * Reactive request context.
     */
    static String describe(ResourceInfo info) {
        if (info == null) {
            return null;
        }
        Class<?> resourceClass = info.getResourceClass();
        Method resourceMethod = info.getResourceMethod();
        if (resourceClass == null || resourceMethod == null) {
            return null;
        }
        return resourceClass.getSimpleName() + "#" + resourceMethod.getName();
    }
}
