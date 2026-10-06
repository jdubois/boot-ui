package sideeffectsapp;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Application code starting threads and creating executors, calling the bridge's thread-activity entry points as the
 * agent's advice on {@code Thread.start} and the executors' constructors and shutdowns would, for the engine's Side
 * Effects tests.
 */
public final class ThreadWork {

    private ThreadWork() {}

    /** Starts a thread that waits for {@code release}: still running when its request ends. */
    public static Thread startWaiting(String name, CountDownLatch release) {
        Thread thread = new Thread(
                () -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                },
                name);
        thread.setDaemon(true);
        start(thread);
        return thread;
    }

    /** Starts a thread and joins it. */
    public static void startAndJoin(String name) throws InterruptedException {
        Thread thread = new Thread(() -> {}, name);
        start(thread);
        thread.join();
    }

    /** Creates an executor, never shut down here. */
    public static ThreadPoolExecutor create() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ThreadActivity.executorCreated(executor, SideEffects.HOOK_TPE_CREATED);
        return executor;
    }

    public static void shutdown(ThreadPoolExecutor executor) {
        ThreadActivity.executorShuttingDown(executor, SideEffects.HOOK_TPE_SHUTDOWN);
        executor.shutdown();
    }

    private static void start(Thread thread) {
        long token = ThreadActivity.threadStarting(thread, SideEffects.HOOK_THREAD_START);
        Throwable thrown = null;
        try {
            thread.start();
        } catch (RuntimeException ex) {
            thrown = ex;
            throw ex;
        } finally {
            ThreadActivity.threadStarted(token, thread, SideEffects.HOOK_THREAD_START, thrown);
        }
    }
}
