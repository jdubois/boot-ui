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
 * /api/side-effects/runtime-version} starts none.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;

    public SideEffectsController(JavaVersionReporter reporter) {
        this.reporter = reporter;
    }

    @GetMapping("/java-version")
    public Mono<Map<String, String>> javaVersion() {
        return Mono.fromCallable(() -> Map.of("version", reporter.version())).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runtime-version")
    public Mono<Map<String, String>> runtimeVersion() {
        return Mono.just(Map.of("version", reporter.runtimeVersion()));
    }
}
