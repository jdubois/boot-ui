package io.github.jdubois.bootui.engine.support;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builders of BootUI's own JDK {@link HttpClient}s (vulnerability and EPSS lookups, GitHub, reachability metadata, HTTP
 * probes, local penetration tests), all running their exchanges on one shared executor of daemon threads named {@code
 * bootui-http-N}. A JDK {@code HttpClient} connects on its executor's thread, where no BootUI frame or flag shows, so
 * the name is what keeps the BootUI agent's {@code network} sensor from reporting BootUI's own connections as the
 * application's ({@code docs/PLAN-v2.md} §5.16, M5-5b). Idle threads end after a minute.
 */
public final class BootUiHttpClients {

    /** The prefix of the shared executor's thread names. */
    public static final String THREAD_PREFIX = "bootui-http-";

    private static final ExecutorService EXECUTOR = executor();

    private BootUiHttpClients() {}

    /** A builder for one of BootUI's own clients, already on the shared executor. */
    public static HttpClient.Builder newBuilder() {
        return HttpClient.newBuilder().executor(EXECUTOR);
    }

    private static ExecutorService executor() {
        AtomicInteger sequence = new AtomicInteger();
        ClassLoader loader = BootUiHttpClients.class.getClassLoader();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, THREAD_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(true);
            // Never the caller's class loader, which a DevTools restart discards.
            thread.setContextClassLoader(loader);
            return thread;
        };
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), threads);
        return executor;
    }
}
