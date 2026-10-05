package bootuiblockingapp;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The blocking behaviors' application code (PLAN-v2 M5-5c), run from a jar, since the agent never rewrites a class
 * loaded from a test root: a helper class, neither a bean nor the harness's own, whose {@code Thread.sleep},
 * {@code TimeUnit.sleep}, and {@code Object.wait} call sites the claim rewrites.
 */
public final class LoopWork {

    private LoopWork() {}

    public static void sleepBriefly(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    public static void sleepAndWait(Object monitor) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(5L);
        synchronized (monitor) {
            monitor.wait(5L);
        }
    }

    /** A handler whose body, a lambda, sleeps. */
    public static Runnable sleepingHandler() {
        return () -> {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Sleeps with the thread interrupted: the sleep throws at once, returned. */
    public static Object interruptedSleep() {
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(1_000L);
            return "not interrupted";
        } catch (InterruptedException expected) {
            return expected;
        }
    }

    public static void lockBriefly(ReentrantLock lock) {
        lock.lock();
        lock.unlock();
    }
}
