package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The resources sensor's seeded routes on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, M5-5g), as on the Spring MVC
 * sample, each run on {@code boundedElastic}, never on the event loop: {@code GET /api/resources/leaked-stream}, {@code
 * /closed-stream}, {@code /pooled-client}, and {@code /collect} ({@link ResourceSeeds}).
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
    public Mono<Map<String, Integer>> leakedStream() {
        return elastic(() -> Map.of("read", seeds.leakStream()));
    }

    @GetMapping("/closed-stream")
    public Mono<Map<String, Integer>> closedStream() {
        return elastic(() -> Map.of("read", seeds.closeStream()));
    }

    @GetMapping("/pooled-client")
    public Mono<Map<String, Integer>> pooledClient() {
        return elastic(() -> Map.of("status", seeds.pooledCall(port())));
    }

    @GetMapping("/collect")
    public Mono<Map<String, Boolean>> collect() {
        return elastic(() -> {
            seeds.collect();
            return Map.of("collected", true);
        });
    }

    private int port() {
        return Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
    }

    private static <T> Mono<T> elastic(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
