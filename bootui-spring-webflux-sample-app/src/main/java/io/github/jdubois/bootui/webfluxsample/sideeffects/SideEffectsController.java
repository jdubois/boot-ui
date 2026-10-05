package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Side Effects' seeded process on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, M5-5a), as on the Spring MVC sample:
 * {@code GET /api/side-effects/java-version} starts the JDK's {@code java -version} on {@code boundedElastic}, never on
 * the event loop, so with the BootUI agent it shows as a {@code java} process started by
 * {@link JavaVersionReporter#version()}, and never its arguments; the counterexample {@code GET
 * /api/side-effects/runtime-version} starts none. {@code GET /api/side-effects/event-loop-sleep} sleeps on the event loop
 * handling the request, a blocking call the Blocking tab reports (M5-5c); its counterexample {@code GET
 * /api/side-effects/worker-sleep} makes the same sleep on {@code boundedElastic}, which it never reports.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;
    private final EventLoopSleeper sleeper;

    public SideEffectsController(JavaVersionReporter reporter, EventLoopSleeper sleeper) {
        this.reporter = reporter;
        this.sleeper = sleeper;
    }

    @GetMapping("/java-version")
    public Mono<Map<String, String>> javaVersion() {
        return Mono.fromCallable(() -> Map.of("version", reporter.version())).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runtime-version")
    public Mono<Map<String, String>> runtimeVersion() {
        return Mono.just(Map.of("version", reporter.runtimeVersion()));
    }

    @GetMapping("/event-loop-sleep")
    public Mono<Map<String, String>> eventLoopSleep() {
        // Deliberately blocking on the event loop the request arrived on: the seed the Blocking tab reports.
        return Mono.fromCallable(() -> Map.of("thread", sleeper.sleepOnEventLoop()));
    }

    @GetMapping("/worker-sleep")
    public Mono<Map<String, String>> workerSleep() {
        return Mono.fromCallable(() -> Map.of("thread", sleeper.sleepOnEventLoop()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
