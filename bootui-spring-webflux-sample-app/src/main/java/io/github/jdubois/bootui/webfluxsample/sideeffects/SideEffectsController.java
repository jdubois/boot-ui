package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Side Effects' seeds on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16), as on the Spring MVC sample. M5-5a: {@code GET
 * /api/side-effects/java-version} starts the JDK's {@code java -version} on {@code boundedElastic}, never on the event
 * loop, so with the BootUI agent it shows as a {@code java} process started by {@link JavaVersionReporter#version()},
 * and never its arguments; the counterexample {@code GET /api/side-effects/runtime-version} starts none. M5-5b: {@code
 * GET /api/side-effects/sdk-call} connects to this application through an SDK's own blocking socket on {@code
 * boundedElastic} ({@link LicenseSdkClient}), a Network row <b>not captured by any panel</b>; the counterexample {@code
 * GET /api/side-effects/rest-call} calls the same endpoint through the recorded {@link WebClient}. M5-5c: {@code GET
 * /api/side-effects/event-loop-sleep} calls the sample's own greeting with the WebClient, then sleeps in its {@code
 * map}, which runs on the Reactor Netty event loop the response completed on, as the {@code event-loop-blocking} seed
 * does: a blocking call the Blocking tab reports. Its counterexample {@code GET /api/side-effects/worker-sleep} makes
 * the same sleep on {@code boundedElastic}, which it never reports.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;
    private final LicenseSdkClient licenses;
    private final EventLoopSleeper sleeper;
    private final WebClient webClient;
    private final Environment environment;

    public SideEffectsController(
            JavaVersionReporter reporter,
            LicenseSdkClient licenses,
            EventLoopSleeper sleeper,
            WebClient webClient,
            Environment environment) {
        this.reporter = reporter;
        this.licenses = licenses;
        this.sleeper = sleeper;
        this.webClient = webClient;
        this.environment = environment;
    }

    @GetMapping("/java-version")
    public Mono<Map<String, String>> javaVersion() {
        return Mono.fromCallable(() -> Map.of("version", reporter.version())).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runtime-version")
    public Mono<Map<String, String>> runtimeVersion() {
        return Mono.just(Map.of("version", reporter.runtimeVersion()));
    }

    @GetMapping("/sdk-call")
    public Mono<Map<String, String>> sdkCall() {
        return Mono.fromCallable(() -> Map.of("status", licenses.check(port())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/rest-call")
    public Mono<Map<String, String>> restCall() {
        return webClient
                .get()
                .uri("http://localhost:" + port() + "/api/side-effects/runtime-version")
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> Map.of("body", body));
    }

    @GetMapping("/event-loop-sleep")
    public Mono<Map<String, String>> eventLoopSleep() {
        // Deliberately blocking on the event loop the greeting's response completed on: the seed the Blocking tab
        // reports.
        return webClient
                .get()
                .uri("http://127.0.0.1:" + port() + "/api/greetings/{name}", "sleep")
                .retrieve()
                .bodyToMono(String.class)
                .map(greeting -> Map.of("thread", sleeper.sleepOnEventLoop()));
    }

    @GetMapping("/worker-sleep")
    public Mono<Map<String, String>> workerSleep() {
        return Mono.fromCallable(() -> Map.of("thread", sleeper.sleepOnEventLoop()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private int port() {
        return Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
    }
}
