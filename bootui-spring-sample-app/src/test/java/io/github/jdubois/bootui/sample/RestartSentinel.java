package io.github.jdubois.bootui.sample;

/**
 * One copy per DevTools restart, loaded by that restart's class loader, numbered, so {@link SpringAgentDevToolsRestartIT}'s
 * heap walk can tell which restarts are still reachable ({@code docs/PLAN-v2.md} M5-1).
 */
public final class RestartSentinel {

    static final String RUN_PROPERTY = "bootui.sample.restart-run";

    /** Starts {@link #keepFirstRun()}'s thread in the first run: the leak test's positive control. */
    static final String MUTATION_PROPERTY = "bootui.sample.restart-mutation";

    /** The thread {@link #keepFirstRun()} starts, named as the agent's own threads are, so the agent's walk roots it. */
    static final String MUTATION_THREAD = "bootui-agent-mutation";

    /** This restart's number: 1 for the first run in a restart class loader. */
    static final Sentinel SENTINEL = new Sentinel(next());

    private RestartSentinel() {}

    /** Loads this restart's copy. */
    static int run() {
        return SENTINEL.run;
    }

    /**
     * A mutation for the leak test: a daemon thread named as an agent thread, running a task of this run's class loader,
     * which it keeps reachable for good, as an agent thread that captured a run would.
     */
    static void keepFirstRun() {
        Thread thread = new Thread(new Keeper(), MUTATION_THREAD);
        thread.setDaemon(true);
        thread.start();
    }

    private static int next() {
        // System.class: one lock for every restart's copy.
        synchronized (System.class) {
            int run = Integer.getInteger(RUN_PROPERTY, 0) + 1;
            System.setProperty(RUN_PROPERTY, String.valueOf(run));
            return run;
        }
    }

    static final class Keeper implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        }
    }

    static final class Sentinel {

        final int run;

        Sentinel(int run) {
            this.run = run;
        }
    }
}
