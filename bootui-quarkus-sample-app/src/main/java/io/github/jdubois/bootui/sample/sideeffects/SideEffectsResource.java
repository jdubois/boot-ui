package io.github.jdubois.bootui.sample.sideeffects;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Side Effects' seeded process on Quarkus ({@code docs/PLAN-v2.md} §5.16, M5-5a), as on the Spring sample: {@code GET
 * /api/side-effects/java-version} runs on a worker thread and starts the JDK's {@code java -version}, so with the BootUI
 * agent it shows as a {@code java} process started by {@link JavaVersionReporter#version()}, and never its arguments;
 * the counterexample {@code GET /api/side-effects/runtime-version} starts none.
 */
@Path("/api/side-effects")
@Produces(MediaType.APPLICATION_JSON)
public class SideEffectsResource {

    private final JavaVersionReporter reporter;

    public SideEffectsResource(JavaVersionReporter reporter) {
        this.reporter = reporter;
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
}
