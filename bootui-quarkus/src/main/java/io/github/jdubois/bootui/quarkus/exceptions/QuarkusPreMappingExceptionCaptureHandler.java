package io.github.jdubois.bootui.quarkus.exceptions;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;

/**
 * Captures a JAX-RS resource-method exception into the shared {@link ExceptionStore} <strong>before</strong>
 * RESTEasy Reactive resolves it to a response — including exceptions that an application's own {@code
 * jakarta.ws.rs.ext.ExceptionMapper} (or RESTEasy Reactive's lower-ceremony {@code @ServerExceptionMapper})
 * handles and maps to a deliberately-not-logged response. This is the Quarkus analogue of the Spring
 * adapter's {@code BootUiExceptionHandlerResolver}, which observes <em>every</em> MVC handler exception —
 * including ones a later {@code @ExceptionHandler}/{@code @ControllerAdvice} goes on to resolve — because it
 * is a {@code HandlerExceptionResolver} at {@code Ordered.HIGHEST_PRECEDENCE} that always returns {@code
 * null} to defer.
 *
 * <p>Without this feeder, an application-mapped exception is invisible to BootUI whenever the mapper does not
 * itself log the throwable: it never reaches {@code QuarkusErrorHandler} (so {@code
 * QuarkusExceptionLogHandler} never fires), and RESTEasy Reactive never calls {@code
 * RoutingContext.fail(...)} for a mapper-handled exception (so {@code QuarkusExceptionCaptureFilter}'s {@code
 * rc.failure()} check never fires either) — the request simply completes with the mapper's response.
 *
 * <p>Registered as a {@code PreExceptionMapperHandlerBuildItem} — the same first-party RESTEasy Reactive
 * extension point Quarkus' own OpenTelemetry extension uses ({@code AttachExceptionHandler}) to record
 * exception info on the active span before mapping resolves it. Quarkus guarantees this handler runs for
 * <em>every</em> exception about to be mapped, whether or not the application has a matching {@code
 * ExceptionMapper} — so it also fires for the already-covered unhandled-exception case; that is harmless
 * because {@link ExceptionStore#record} dedups by throwable identity across feeders; whichever feeder
 * observes a given throwable first simply wins.
 *
 * <p>Built at <strong>build time</strong> as a plain object (no CDI annotations, no constructor
 * dependencies) — mirroring {@code AttachExceptionHandler} exactly — because {@code
 * PreExceptionMapperHandlerBuildItem} embeds the handler instance directly rather than resolving one through
 * CDI. Its CDI-backed dependencies ({@link ExceptionStore}, the optional {@link TraceIdProvider}, and {@code
 * CurrentVertxRequest}) are therefore resolved lazily, per invocation, via {@link Arc#container()} — cheap for
 * {@code @Singleton}/request-scoped beans and safe even before the container is fully up (guarded to a silent
 * no-op). Every path is wrapped so a diagnostics failure can never disrupt the application's real exception
 * handling or response.
 */
public final class QuarkusPreMappingExceptionCaptureHandler implements ServerRestHandler {

    private static final String BASE_PATH = "/bootui";

    @Override
    public void handle(ResteasyReactiveRequestContext requestContext) throws Exception {
        Throwable thrown = requestContext.getThrowable();
        if (thrown == null) {
            return;
        }
        try {
            ArcContainer container = Arc.container();
            if (container == null || !container.isRunning()) {
                return;
            }
            RoutingContext rc = currentRoutingContext(container);
            String path = rc == null ? null : rc.normalizedPath();
            if (path != null && (path.equals(BASE_PATH) || path.startsWith(BASE_PATH + "/"))) {
                return; // never capture BootUI's own traffic
            }
            InstanceHandle<ExceptionStore> storeHandle = container.instance(ExceptionStore.class);
            if (!storeHandle.isAvailable()) {
                return;
            }
            String method = rc == null ? null : rc.request().method().name();
            String handler = QuarkusResourceHandlers.describe(requestContext.getResteasyReactiveResourceInfo());
            storeHandle
                    .get()
                    .record(
                            thrown,
                            Thread.currentThread().getName(),
                            method,
                            path,
                            handler,
                            "web",
                            currentTraceId(container));
        } catch (RuntimeException ignored) {
            // Diagnostics capture must never interfere with the application's real exception handling.
        }
    }

    /**
     * The active request's routing context, or {@code null} when unavailable (no active request scope, or
     * {@code quarkus-vertx-http}'s {@code CurrentVertxRequest} bean is not resolvable for any reason).
     */
    private static RoutingContext currentRoutingContext(ArcContainer container) {
        try {
            InstanceHandle<CurrentVertxRequest> handle = container.instance(CurrentVertxRequest.class);
            return handle.isAvailable() ? handle.get().getCurrent() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The active span's trace id, or {@code null} when OpenTelemetry is absent (no {@link TraceIdProvider}
     * bean) or no span is in context. Fully guarded so capture never disrupts request handling.
     */
    private static String currentTraceId(ArcContainer container) {
        try {
            InstanceHandle<TraceIdProvider> handle = container.instance(TraceIdProvider.class);
            return handle.isAvailable() ? handle.get().currentTraceId() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
