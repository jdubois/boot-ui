package io.github.jdubois.bootui.quarkus.logging;

import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Installs and removes the {@link QuarkusLogTailHandler} on the root {@code java.util.logging} logger
 * across the application lifecycle, mirroring how the Spring adapter installs its Logback appender. The
 * handler is attached at {@link StartupEvent} (runtime, after Quarkus has installed the JBoss LogManager —
 * not at build/static-init, which would bake the wrong manager into a native image) and detached at
 * {@link ShutdownEvent} so a dev-mode live reload never leaves a stale handler pinning the previous
 * buffer. Install is idempotent: any pre-existing BootUI handler is removed first.
 */
@ApplicationScoped
public class QuarkusLogTailCapture {

    private final LogTailBuffer buffer;
    private QuarkusLogTailHandler handler;

    @Inject
    public QuarkusLogTailCapture(LogTailBuffer buffer) {
        this.buffer = buffer;
    }

    void onStart(@Observes StartupEvent event) {
        Logger root = Logger.getLogger("");
        for (Handler existing : List.of(root.getHandlers())) {
            if (existing instanceof QuarkusLogTailHandler) {
                root.removeHandler(existing);
            }
        }
        handler = new QuarkusLogTailHandler(
                buffer,
                new InternalPackageMatcher(List.of(
                        "io.github.jdubois.bootui.quarkus",
                        "io.github.jdubois.bootui.engine",
                        "io.github.jdubois.bootui.core")));
        root.addHandler(handler);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (handler != null) {
            Logger.getLogger("").removeHandler(handler);
            handler = null;
        }
    }
}
