package bootuireloadapp;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The minimal application {@code BootUiAgentLiveReloadLeakIT} edits ten times, each edit a Quarkus live reload, in a
 * package of its own so the BootUI agent claims and instruments it as an application's: each run's class loader loads
 * its own copy, numbered by {@link #SENTINEL}, so a heap walk can tell which runs are still reachable.
 */
@Path("/reload-probe")
public class ReloadProbeResource {

    static final Sentinel SENTINEL = new Sentinel(next());

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String probe() {
        if (SENTINEL.run == 1 && Boolean.getBoolean(MUTATION_PROPERTY)) {
            keepFirstRun();
        }
        return "v0 run " + SENTINEL.run;
    }

    /** Starts {@link #keepFirstRun()}'s thread in the first run: the leak test's positive control. */
    public static final String MUTATION_PROPERTY = "bootui.reload.mutation";

    /** The thread {@link #keepFirstRun()} starts, named as the agent's own threads are, so the agent's walk roots it. */
    public static final String MUTATION_THREAD = "bootui-agent-mutation";

    /**
     * A mutation for the leak test: once, a daemon thread named as an agent thread, running a task of this run's class
     * loader, which it keeps reachable for good, as an agent thread that captured a run would.
     */
    static synchronized void keepFirstRun() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (MUTATION_THREAD.equals(thread.getName())) {
                return;
            }
        }
        Thread thread = new Thread(new Keeper(), MUTATION_THREAD);
        thread.setDaemon(true);
        thread.start();
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

    private static int next() {
        // System.class: one lock for every run's copy.
        synchronized (System.class) {
            int run = Integer.getInteger("bootui.reload.run", 0) + 1;
            System.setProperty("bootui.reload.run", String.valueOf(run));
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
