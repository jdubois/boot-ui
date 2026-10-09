package io.github.jdubois.bootui.sample.sideeffects;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The resources sensor's seeded routes ({@code docs/PLAN-v2.md} §5.16, M5-5g): {@code GET /api/resources/leaked-stream}
 * drops a stream never closed, {@code GET /api/resources/closed-stream} closes it in try-with-resources, {@code GET
 * /api/resources/pooled-client} leaves a pooled connection open past its request, and {@code GET
 * /api/resources/collect} asks the collector to run, so the e2e suites need not wait for it ({@link ResourceSeeds}).
 */
@RestController
@RequestMapping("/api/resources")
public class ResourcesController {

    private final ResourceSeeds seeds;
    private final Environment environment;

    public ResourcesController(ResourceSeeds seeds, Environment environment) {
        this.seeds = seeds;
        this.environment = environment;
    }

    @GetMapping("/leaked-stream")
    public Map<String, Integer> leakedStream() throws Exception {
        return Map.of("read", seeds.leakStream());
    }

    @GetMapping("/closed-stream")
    public Map<String, Integer> closedStream() throws Exception {
        return Map.of("read", seeds.closeStream());
    }

    @GetMapping("/pooled-client")
    public Map<String, Integer> pooledClient() throws Exception {
        return Map.of("status", seeds.pooledCall(port()));
    }

    @GetMapping("/collect")
    public Map<String, Boolean> collect() {
        seeds.collect();
        return Map.of("collected", true);
    }

    private int port() {
        return Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
    }
}
