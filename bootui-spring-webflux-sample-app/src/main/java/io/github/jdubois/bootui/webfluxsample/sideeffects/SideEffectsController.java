package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Side Effects' seeded process on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, M5-5a), and its file and property read
 * (M5-5d, {@code GET /api/side-effects/report}, see {@link ReportWriter}), as on the Spring MVC sample:
 * {@code GET /api/side-effects/java-version} starts the JDK's {@code java -version} on {@code boundedElastic}, never on
 * the event loop, so with the BootUI agent it shows as a {@code java} process started by
 * {@link JavaVersionReporter#version()}, and never its arguments; the counterexample {@code GET
 * /api/side-effects/runtime-version} starts none.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;
    private final ReportWriter reports;

    public SideEffectsController(JavaVersionReporter reporter, ReportWriter reports) {
        this.reporter = reporter;
        this.reports = reports;
    }

    /** Writes a report outside the temporary directory and reads a system property, on boundedElastic (M5-5d). */
    @GetMapping("/report")
    public Mono<Map<String, String>> report() {
        return Mono.fromCallable(() -> Map.of("report", reports.writeReport()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** The counterexample: a temporary file, written and deleted. */
    @GetMapping("/scratch")
    public Mono<Map<String, String>> scratch() {
        return Mono.fromCallable(() -> Map.of("scratch", reports.scratch())).subscribeOn(Schedulers.boundedElastic());
    }

    /** The counterexample: a JDK logging handler's file, grouped apart as logging. */
    @GetMapping("/log")
    public Mono<Map<String, String>> log() {
        return Mono.fromCallable(() -> Map.of("log", reports.log())).subscribeOn(Schedulers.boundedElastic());
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
