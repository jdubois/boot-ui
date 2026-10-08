package io.github.jdubois.bootui.sample.sideeffects;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The resources sensor's seeded routes on Quarkus ({@code docs/PLAN-v2.md} §5.16, M5-5g), as on the Spring sample,
 * each run on a worker thread: {@code GET /api/resources/leaked-stream}, {@code /closed-stream}, {@code
 * /pooled-client}, and {@code /collect} ({@link ResourceSeeds}).
 */
@Path("/api/resources")
@Produces(MediaType.APPLICATION_JSON)
public class ResourcesResource {

    private final ResourceSeeds seeds;

    public ResourcesResource(ResourceSeeds seeds) {
        this.seeds = seeds;
    }

    @GET
    @Path("/leaked-stream")
    public Map<String, Integer> leakedStream() throws Exception {
        return Map.of("read", seeds.leakStream());
    }

    @GET
    @Path("/closed-stream")
    public Map<String, Integer> closedStream() throws Exception {
        return Map.of("read", seeds.closeStream());
    }

    @GET
    @Path("/pooled-client")
    public Map<String, Integer> pooledClient() throws Exception {
        return Map.of("status", seeds.pooledCall(port()));
    }

    @GET
    @Path("/collect")
    public Map<String, Boolean> collect() {
        seeds.collect();
        return Map.of("collected", true);
    }

    private static int port() {
        boolean test = io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST;
        return ConfigProvider.getConfig()
                .getOptionalValue(test ? "quarkus.http.test-port" : "quarkus.http.port", Integer.class)
                .orElse(test ? 8083 : 8082);
    }
}
