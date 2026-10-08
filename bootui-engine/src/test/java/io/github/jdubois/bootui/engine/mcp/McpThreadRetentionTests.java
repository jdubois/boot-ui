package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The MCP worker pools are JVM-wide, so a worker created while a DevTools restart or Quarkus dev class loader is current
 * must keep nothing of it: not as its context class loader, not through an inherited thread-local, and not through the
 * access control context of the creating stack (JDK 17 to 23).
 */
class McpThreadRetentionTests {

    private static final InheritableThreadLocal<Object> INHERITED = new InheritableThreadLocal<>();
    private static final String WORKER_PREFIX = "bootui-mcp-";

    @Test
    void streamingCallWorkersKeepNoApplicationClassLoader() throws Exception {
        assertCollected(runAsApplication(McpThreadRetentionTests::streamingCalls));
    }

    @Test
    void blockingCallWorkersKeepNoApplicationClassLoader() throws Exception {
        assertCollected(runAsApplication(McpThreadRetentionTests::blockingCalls));
    }

    /** Streams more concurrent calls than there are MCP workers, so the cached pools must create new ones. */
    private static void streamingCalls(ClassLoader application) {
        Set<String> before = mcpWorkers();
        int calls = before.size() + 2;
        CountDownLatch entered = new CountDownLatch(calls);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sawApplicationLoader = new AtomicInteger();
        McpDispatcher dispatcher = dispatcher(calls, entered, release, sawApplicationLoader, application);
        List<CountDownLatch> closed = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            McpCallStart start = dispatcher.start(request(i), true);
            assertThat(start).isInstanceOf(McpCallStart.Stream.class);
            CountDownLatch done = new CountDownLatch(1);
            closed.add(done);
            ((McpCallStart.Stream) start).call().start(new ClosingSink(done));
        }
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(newWorkers(before, "bootui-mcp-tool-")).isNotEmpty();
            assertThat(newWorkers(before, "bootui-mcp-stream-")).isNotEmpty();
            release.countDown();
            for (CountDownLatch done : closed) {
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            }
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
        }
        // Each tool ran with the application's class loader as its context class loader.
        assertThat(sawApplicationLoader).hasValue(calls);
    }

    /** Blocks more concurrent callers than there are MCP workers in the tool executor. */
    private static void blockingCalls(ClassLoader application) {
        Set<String> before = mcpWorkers();
        int calls = before.size() + 2;
        CountDownLatch entered = new CountDownLatch(calls);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sawApplicationLoader = new AtomicInteger();
        McpDispatcher dispatcher = dispatcher(calls, entered, release, sawApplicationLoader, application);
        AtomicInteger answered = new AtomicInteger();
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            int id = i;
            Thread caller = new Thread(() -> {
                if (dispatcher.dispatch(request(id)) instanceof ToolCallResult) {
                    answered.incrementAndGet();
                }
            });
            caller.setContextClassLoader(application);
            callers.add(caller);
            caller.start();
        }
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(newWorkers(before, "bootui-mcp-tool-")).isNotEmpty();
            release.countDown();
            for (Thread caller : callers) {
                caller.join(TimeUnit.SECONDS.toMillis(10));
                assertThat(caller.isAlive()).isFalse();
            }
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
        }
        assertThat(answered).hasValue(calls);
        assertThat(sawApplicationLoader).hasValue(calls);
    }

    private static McpDispatcher dispatcher(
            int calls,
            CountDownLatch entered,
            CountDownLatch release,
            AtomicInteger sawApplicationLoader,
            ClassLoader application) {
        WeakReference<ClassLoader> expected = new WeakReference<>(application);
        McpTool tool =
                new McpTool("architecture_scan", "Scan.", McpToolSchema.NONE, BootUiPanels.ARCHITECTURE, true, a -> {
                    if (Thread.currentThread().getContextClassLoader() == expected.get()) {
                        sawApplicationLoader.incrementAndGet();
                    }
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "done";
                });
        return new McpDispatcher(
                List.of(tool), List.of(), new AllowAll(), "1.0", "x", 50, calls, 30_000, (operation, failure) -> {});
    }

    /**
     * Runs {@code work} on a fresh thread as application code would: a frame of a throwaway class loader on the stack,
     * that loader as the context class loader, and an inheritable thread-local holding it. Returns the loader weakly.
     */
    private static WeakReference<ClassLoader> runAsApplication(Consumer<ClassLoader> work) throws Exception {
        ThrowawayLoader application = new ThrowawayLoader(McpThreadRetentionTests.class.getClassLoader());
        Class<?> probeType = application.loadClass(ApplicationFrameProbe.class.getName());
        assertThat(probeType.getClassLoader()).isSameAs(application);
        Runnable probe = (Runnable) probeType.getConstructor(Runnable.class).newInstance((Runnable)
                () -> work.accept(Thread.currentThread().getContextClassLoader()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(application);
            INHERITED.set(application);
            try {
                probe.run();
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                INHERITED.remove();
                Thread.currentThread().setContextClassLoader(null);
            }
        });
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(thread.isAlive()).isFalse();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return new WeakReference<>(application);
    }

    private static void assertCollected(WeakReference<ClassLoader> loader) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (loader.get() != null && System.nanoTime() < deadline) {
            System.gc();
            byte[][] pressure = new byte[16][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[1 << 16];
            }
            Thread.sleep(50);
        }
        assertThat(loader.get())
                .as("a class loader current when the MCP workers were created is collected")
                .isNull();
    }

    private static Set<String> mcpWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith(WORKER_PREFIX))
                .collect(Collectors.toSet());
    }

    private static Set<String> newWorkers(Set<String> before, String prefix) {
        return mcpWorkers().stream()
                .filter(name -> name.startsWith(prefix) && !before.contains(name))
                .collect(Collectors.toSet());
    }

    private static McpRequest request(int id) {
        return new McpRequest(
                "2.0",
                "tools/call",
                false,
                null,
                "architecture_scan",
                null,
                null,
                null,
                Set.of(),
                null,
                null,
                null,
                McpEra.MODERN,
                McpProgressToken.of(id + 1));
    }

    /** Defines {@link ApplicationFrameProbe} itself, as a restart class loader defines the application's classes. */
    private static final class ThrowawayLoader extends ClassLoader {

        private final String probe = ApplicationFrameProbe.class.getName();

        ThrowawayLoader(ClassLoader parent) {
            super("throwaway-application", parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!probe.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String resource = name.replace('.', '/') + ".class";
                    try (InputStream in = getParent().getResourceAsStream(resource)) {
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception ex) {
                        throw new ClassNotFoundException(name, ex);
                    }
                }
                return loaded;
            }
        }
    }

    private static final class ClosingSink implements McpStreamSink {

        private final CountDownLatch closed;

        ClosingSink(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void progress(McpProgressToken token, ProgressEvent event) {}

        @Override
        public void heartbeat() {}

        @Override
        public void complete(McpDispatchOutcome outcome) {}

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class AllowAll implements McpPanelPolicy {

        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return null;
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return null;
        }
    }
}
