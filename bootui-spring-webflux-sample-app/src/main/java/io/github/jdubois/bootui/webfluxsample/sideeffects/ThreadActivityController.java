package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Thread activity's seeded routes on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, M5-5e), as on the Spring MVC
 * sample, each run on {@code boundedElastic}, never on the event loop: {@code GET /api/thread-activity/left-running}
 * leaves a {@code report-refresher-{n}} thread running and {@code GET /api/thread-activity/own-pool} an executor never
 * shut down; their counterexamples, {@code GET /api/thread-activity/joined} and {@code GET
 * /api/thread-activity/closed-pool}, leave nothing running.
 */
@RestController
@RequestMapping("/api/thread-activity")
public class ThreadActivityController {

    private final BackgroundWork work;

    public ThreadActivityController(BackgroundWork work) {
        this.work = work;
    }

    @GetMapping("/left-running")
    public Mono<Map<String, String>> leftRunning() {
        return elastic(() -> Map.of("thread", work.startRefresher()));
    }

    @GetMapping("/joined")
    public Mono<Map<String, String>> joined() {
        return elastic(() -> Map.of("thread", work.refreshNow()));
    }

    @GetMapping("/own-pool")
    public Mono<Map<String, Integer>> ownPool() {
        return elastic(() -> Map.of("result", work.exportWithOwnPool()));
    }

    @GetMapping("/closed-pool")
    public Mono<Map<String, Integer>> closedPool() {
        return elastic(() -> Map.of("result", work.exportWithClosedPool()));
    }

    private static <T> Mono<T> elastic(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
