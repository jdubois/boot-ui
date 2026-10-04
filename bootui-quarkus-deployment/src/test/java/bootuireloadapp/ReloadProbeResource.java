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
        return "v0 run " + SENTINEL.run;
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
