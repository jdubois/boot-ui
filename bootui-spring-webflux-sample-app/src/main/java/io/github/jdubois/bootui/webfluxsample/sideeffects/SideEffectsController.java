package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Map;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Side Effects' seeded process on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, M5-5a), as on the Spring MVC sample:
 * {@code GET /api/side-effects/java-version} starts the JDK's {@code java -version} on {@code boundedElastic}, never on
 * the event loop, so with the BootUI agent it shows as a {@code java} process started by
 * {@link JavaVersionReporter#version()}, and never its arguments; the counterexample {@code GET
 * /api/side-effects/runtime-version} starts none. {@code GET /api/side-effects/event-loop-sleep} calls the sample's own
 * greeting with a WebClient, then sleeps in its {@code map}, which runs on the Reactor Netty event loop the response
 * completed on, as the {@code event-loop-blocking} seed does: a blocking call the Blocking tab reports (M5-5c). Its
 * counterexample {@code GET /api/side-effects/worker-sleep} makes the same sleep on {@code boundedElastic}, which it
 * never reports.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;
    private final EventLoopSleeper sleeper;
    private final WebClient webClient;
    private final WebServerApplicationContext context;

    public SideEffectsController(
            JavaVersionReporter reporter,
            EventLoopSleeper sleeper,
            WebClient.Builder webClients,
            WebServerApplicationContext context) {
        this.reporter = reporter;
        this.sleeper = sleeper;
        this.webClient = webClients.build();
        this.context = context;
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
        // Deliberately blocking on the event loop the greeting's response completed on: the seed the Blocking tab
        // reports.
        return webClient
                .get()
                .uri("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/greetings/{name}", "sleep")
                .retrieve()
                .bodyToMono(String.class)
                .map(greeting -> Map.of("thread", sleeper.sleepOnEventLoop()));
    }

    @GetMapping("/worker-sleep")
    public Mono<Map<String, String>> workerSleep() {
        return Mono.fromCallable(() -> Map.of("thread", sleeper.sleepOnEventLoop()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
