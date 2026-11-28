package io.github.jdubois.bootui.quarkus4.logging;

import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
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
 * Installs and removes the {@link QuarkusLogTailHandler} on the root {@code java.util.logging} logger
 * across the application lifecycle, mirroring how the Spring adapter installs its Logback appender. The
 * handler is attached at {@link StartupEvent} (runtime, after Quarkus has installed the JBoss LogManager —
 * not at build/static-init, which would bake the wrong manager into a native image) and detached at
 * {@link ShutdownEvent} so a dev-mode live reload never leaves a stale handler pinning the previous
 * buffer. Install is idempotent: any pre-existing BootUI handler is removed first.
 */
@ApplicationScoped
public class QuarkusLogTailCapture {

    private final java.util.function.Supplier<LogTailBuffer> buffer;
    private final java.util.function.Supplier<RuntimeJournal> journal;
    private final java.util.function.Supplier<QuarkusLogCoverage> coverage;
    private QuarkusLogTailHandler handler;
    private Logger captureRoot;

    public QuarkusLogTailCapture(LogTailBuffer buffer) {
        this(buffer, null);
    }

    /** @param journal the runtime journal, which also receives {@code WARN} and {@code ERROR} events, or {@code null} */
    public QuarkusLogTailCapture(LogTailBuffer buffer, RuntimeJournal journal) {
        this(buffer, journal, new QuarkusLogCoverage());
    }

    @Inject
    public QuarkusLogTailCapture(
            Instance<LogTailBuffer> buffer, Instance<RuntimeJournal> journal, Instance<QuarkusLogCoverage> coverage) {
        this.buffer = buffer::get;
        this.journal = journal::get;
        this.coverage = coverage::get;
    }

    public QuarkusLogTailCapture(LogTailBuffer buffer, RuntimeJournal journal, QuarkusLogCoverage coverage) {
        this.buffer = () -> buffer;
        this.journal = () -> journal;
        this.coverage = () -> coverage;
    }

    void onStart(@Observes StartupEvent event) {
        if (BootUiNativeActivation.isNativeInactive()) {
            return;
        }
        Logger root = Logger.getLogger("");
        for (Handler existing : List.of(root.getHandlers())) {
            if (existing instanceof QuarkusLogTailHandler) {
                root.removeHandler(existing);
            }
        }
        handler = new QuarkusLogTailHandler(
                buffer.get(),
                new InternalPackageMatcher(List.of(
                        "io.github.jdubois.bootui.quarkus4",
                        "io.github.jdubois.bootui.engine",
                        "io.github.jdubois.bootui.core")),
                journal.get());
        root.addHandler(handler);
        captureRoot = root;
        coverage.get().handlerChanged(root, handler);
    }

    void onStop(@Observes ShutdownEvent event) {
        Logger root = captureRoot;
        QuarkusLogTailHandler captured = handler;
        captureRoot = null;
        handler = null;
        try {
            if (root != null && captured != null) {
                root.removeHandler(captured);
            }
        } finally {
            if (captured != null) {
                coverage.get().handlerChanged(null, null);
            }
        }
    }
}
