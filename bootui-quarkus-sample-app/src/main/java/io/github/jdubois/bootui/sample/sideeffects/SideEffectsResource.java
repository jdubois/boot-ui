package io.github.jdubois.bootui.sample.sideeffects;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.NonBlocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Side Effects' seeded process on Quarkus ({@code docs/PLAN-v2.md} §5.16, M5-5a), as on the Spring sample: {@code GET
 * /api/side-effects/java-version} runs on a worker thread and starts the JDK's {@code java -version}, so with the BootUI
 * agent it shows as a {@code java} process started by {@link JavaVersionReporter#version()}, and never its arguments;
 * the counterexample {@code GET /api/side-effects/runtime-version} starts none. {@code GET
 * /api/side-effects/event-loop-sleep}, {@link NonBlocking}, sleeps on the Vert.x event loop handling the request, a
 * blocking call the Blocking tab reports (M5-5c); its counterexample {@code GET /api/side-effects/worker-sleep} makes the
 * same sleep on a worker thread, which it never reports.
 */
@Path("/api/side-effects")
@Produces(MediaType.APPLICATION_JSON)
public class SideEffectsResource {

    private final JavaVersionReporter reporter;
    private final EventLoopSleeper sleeper;

    public SideEffectsResource(JavaVersionReporter reporter, EventLoopSleeper sleeper) {
        this.reporter = reporter;
        this.sleeper = sleeper;
    }

    @GET
    @Path("/java-version")
    public Map<String, String> javaVersion() {
        return Map.of("version", reporter.version());
    }

    @GET
    @Path("/runtime-version")
    public Map<String, String> runtimeVersion() {
        return Map.of("version", reporter.runtimeVersion());
    }

    @GET
    @Path("/event-loop-sleep")
    @NonBlocking
    public Map<String, String> eventLoopSleep() {
        // Deliberately blocking on the event loop: the seed the Blocking tab reports.
        return Map.of("thread", sleeper.sleepOnEventLoop());
    }

    @GET
    @Path("/worker-sleep")
    @Blocking
    public Map<String, String> workerSleep() {
        return Map.of("thread", sleeper.sleepOnEventLoop());
    }
}
