package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Captures unhandled HTTP request failures into the shared {@link ExceptionStore} — the Quarkus analogue
 * of Spring's MVC {@code HandlerExceptionResolver}, which has no Quarkus counterpart. Registered as a
 * Vert.x route filter (like {@code QuarkusHttpExchangeCaptureFilter}); it is wired only in dev/test launch
 * modes, so production stays dark. It records in {@link RoutingContext#addBodyEndHandler} so a failure set
 * by a downstream handler is final, and only ever observes — it never short-circuits the response.
 *
 * <p>BootUI's own {@code /bootui} traffic is excluded so the panel never captures its internals, and the
 * request path is taken without its query string to avoid surfacing secrets. The store dedups by
 * cause-chain identity, so a failure also logged by Quarkus (and seen by {@code QuarkusExceptionLogHandler})
 * counts once, with this filter supplying the richer request context.</p>
 */
@ApplicationScoped
public class QuarkusExceptionCaptureFilter {

    private static final String BASE_PATH = "/bootui";

    /** After the safety filter (priority 1000); only ever records, never short-circuits. */
    private static final int PRIORITY = 900;

    private final ExceptionStore store;

    @Inject
    public QuarkusExceptionCaptureFilter(ExceptionStore store) {
        this.store = store;
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (path != null && (path.equals(BASE_PATH) || path.startsWith(BASE_PATH + "/"))) {
            rc.next();
            return;
        }
        String method = rc.request().method().name();
        rc.addBodyEndHandler(v -> {
            Throwable failure = rc.failure();
            if (failure != null) {
                store.record(failure, Thread.currentThread().getName(), method, path, null, "web");
            }
        });
        rc.next();
    }
}
