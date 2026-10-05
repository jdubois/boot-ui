package io.github.jdubois.bootui.sample.sideeffects;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Thread activity's seeded routes on Quarkus ({@code docs/PLAN-v2.md} §5.16, M5-5e), as on the Spring sample, each run
 * on a worker thread: {@code GET /api/thread-activity/left-running} leaves a {@code report-refresher-{n}} thread running
 * and {@code GET /api/thread-activity/own-pool} an executor never shut down; their counterexamples, {@code GET
 * /api/thread-activity/joined} and {@code GET /api/thread-activity/closed-pool}, leave nothing running.
 */
@Path("/api/thread-activity")
@Produces(MediaType.APPLICATION_JSON)
public class ThreadActivityResource {

    private final BackgroundWork work;

    public ThreadActivityResource(BackgroundWork work) {
        this.work = work;
    }

    @GET
    @Path("/left-running")
    public Map<String, String> leftRunning() {
        return Map.of("thread", work.startRefresher());
    }

    @GET
    @Path("/joined")
    public Map<String, String> joined() throws InterruptedException {
        return Map.of("thread", work.refreshNow());
    }

    @GET
    @Path("/own-pool")
    public Map<String, Integer> ownPool() throws Exception {
        return Map.of("result", work.exportWithOwnPool());
    }

    @GET
    @Path("/closed-pool")
    public Map<String, Integer> closedPool() throws Exception {
        return Map.of("result", work.exportWithClosedPool());
    }
}
