package io.github.jdubois.bootui.agent;

import java.util.concurrent.TimeUnit;

/**
 * The blocking sensor's call-site self-test probe (PLAN-v2 §5.16, M5-5c): rewritten by the application-methods
 * transformer whatever the claimed packages, named by string so it loads late, and never inventoried or timed. Its
 * {@code Thread.sleep}, {@code TimeUnit.sleep}, and {@code Object.wait} call sites must reach the bridge's substitutes.
 */
final class BlockingProbe {

    private BlockingProbe() {}

    /** Sleeps twice and waits once, a millisecond each: the substitutes count two sleeps and one wait. */
    static int ping() throws InterruptedException {
        Thread.sleep(1L);
        TimeUnit.MILLISECONDS.sleep(1L);
        Object monitor = new Object();
        synchronized (monitor) {
            monitor.wait(1L);
        }
        return 3;
    }
}
