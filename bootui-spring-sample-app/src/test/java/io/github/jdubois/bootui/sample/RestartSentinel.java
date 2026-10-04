package io.github.jdubois.bootui.sample;

/**
 * One copy per DevTools restart, loaded by that restart's class loader, numbered, so {@link SpringAgentDevToolsRestartIT}'s
 * heap walk can tell which restarts are still reachable ({@code docs/PLAN-v2.md} M5-1).
 */
public final class RestartSentinel {

    static final String RUN_PROPERTY = "bootui.sample.restart-run";

    /** This restart's number: 1 for the first run in a restart class loader. */
    static final Sentinel SENTINEL = new Sentinel(next());

    private RestartSentinel() {}

    /** Loads this restart's copy. */
    static int run() {
        return SENTINEL.run;
    }

    private static int next() {
        // System.class: one lock for every restart's copy.
        synchronized (System.class) {
            int run = Integer.getInteger(RUN_PROPERTY, 0) + 1;
            System.setProperty(RUN_PROPERTY, String.valueOf(run));
            return run;
        }
    }

    static final class Sentinel {

        final int run;

        Sentinel(int run) {
            this.run = run;
        }
    }
}
