package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BootUiThreadsTests {

    private static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();

    @Test
    void threadsInheritNeitherThreadLocalsNorTheCallersContextClassLoader() throws Exception {
        ClassLoader application = new URLClassLoader(new URL[0], null);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(application);
        INHERITED.set("request");
        AtomicReference<String> seen = new AtomicReference<>("unset");
        Thread thread;
        try {
            ThreadFactory factory = BootUiThreads.daemonFactory("bootui-test-", BootUiThreads.ENGINE_LOADER);
            thread = factory.newThread(() -> seen.set(INHERITED.get()));
        } finally {
            INHERITED.remove();
            Thread.currentThread().setContextClassLoader(previous);
        }

        assertThat(thread.getName()).isEqualTo("bootui-test-1");
        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.getContextClassLoader()).isSameAs(BootUiThreads.ENGINE_LOADER);
        thread.start();
        thread.join(5_000);
        assertThat(seen.get()).isNull();
    }

    @Test
    void aNullContextClassLoaderIsKept() {
        Thread thread = BootUiThreads.newDaemonThread("bootui-test", () -> {}, null);

        assertThat(thread.getContextClassLoader()).isNull();
    }

    @Test
    void theContextClassLoaderIsSetOnlyWhileTheTaskRuns() {
        ClassLoader application = new URLClassLoader(new URL[0], null);
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        AtomicReference<ClassLoader> during = new AtomicReference<>();

        BootUiThreads.runWithContextLoader(
                application, () -> during.set(Thread.currentThread().getContextClassLoader()));

        assertThat(during.get()).isSameAs(application);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }

    @Test
    void aTaskThatChangesTheContextClassLoaderLeavesNoTrace() {
        ClassLoader leaked = new URLClassLoader(new URL[0], null);
        ClassLoader before = Thread.currentThread().getContextClassLoader();

        BootUiThreads.runWithContextLoader(before, () -> Thread.currentThread().setContextClassLoader(leaked));
        BootUiThreads.runWithContextLoader(null, () -> Thread.currentThread().setContextClassLoader(leaked));

        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }
}
