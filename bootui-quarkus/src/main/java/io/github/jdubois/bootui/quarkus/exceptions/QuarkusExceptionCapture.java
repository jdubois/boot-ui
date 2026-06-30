package io.github.jdubois.bootui.quarkus.exceptions;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Installs and removes the {@link QuarkusExceptionLogHandler} on the root {@code java.util.logging} logger
 * across the application lifecycle, mirroring how the Spring adapter installs its Logback appender and the
 * Quarkus Log Tail adapter installs its log handler. The handler is attached at {@link StartupEvent}
 * (runtime, after Quarkus has installed the JBoss LogManager — not at build/static-init, which would bake
 * the wrong manager into a native image) and detached at {@link ShutdownEvent} so a dev-mode live reload
 * never leaves a stale handler pinning the previous store. Install is idempotent: any pre-existing BootUI
 * handler is removed first.
 */
@ApplicationScoped
public class QuarkusExceptionCapture {

    private final ExceptionStore store;
    private final TraceIdProvider traceIdProvider;
    private QuarkusExceptionLogHandler handler;

    @Inject
    public QuarkusExceptionCapture(ExceptionStore store, Instance<TraceIdProvider> traceIdProvider) {
        this.store = store;
        this.traceIdProvider = traceIdProvider.isResolvable() ? traceIdProvider.get() : null;
    }

    void onStart(@Observes StartupEvent event) {
        Logger root = Logger.getLogger("");
        for (Handler existing : List.of(root.getHandlers())) {
            if (existing instanceof QuarkusExceptionLogHandler) {
                root.removeHandler(existing);
            }
        }
        handler = new QuarkusExceptionLogHandler(
                store,
                new InternalPackageMatcher(List.of(
                        "io.github.jdubois.bootui.quarkus",
                        "io.github.jdubois.bootui.engine",
                        "io.github.jdubois.bootui.core")),
                traceIdProvider);
        root.addHandler(handler);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (handler != null) {
            Logger.getLogger("").removeHandler(handler);
            handler = null;
        }
    }
}
