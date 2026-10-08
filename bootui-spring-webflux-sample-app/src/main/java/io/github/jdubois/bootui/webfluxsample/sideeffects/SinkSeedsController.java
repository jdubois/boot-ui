package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Security sinks' seeds on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, §5.17 {@code request-input-in-sink}, M5-6b).
 * With the BootUI agent's {@code security-sinks} sensor and {@code bootui.agent.security-sinks.request-values=true}:
 * {@code GET /api/sinks/reports/{name}} reads a file under {@code reports/{name}} on a bounded-elastic thread, where
 * Reactor's context propagation carries the request, and {@code GET /api/sinks/lookup?name=…} calls a URL holding the
 * value through the recorded {@link WebClient}. This sample uses R2DBC, which SQL Trace does not capture, so SQL text is
 * not checked here.
 */
@RestController
@RequestMapping("/api/sinks")
public class SinkSeedsController {

    private final WebClient webClient;
    private final Environment environment;

    public SinkSeedsController(WebClient webClient, Environment environment) {
        this.webClient = webClient;
        this.environment = environment;
    }

    /** Request input in a file path: a report read under {@code reports/{name}}. */
    @GetMapping("/reports/{name}")
    public Mono<Map<String, Object>> report(@PathVariable String name) {
        return Mono.fromCallable(() -> read(name)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Request input in an outbound URL, through the recorded WebClient. */
    @GetMapping("/lookup")
    public Mono<Map<String, String>> lookup(@RequestParam(name = "name", defaultValue = "alice") String name) {
        return webClient
                .get()
                .uri("http://localhost:" + port() + "/api/side-effects/runtime-version?user={name}", name)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> Map.of("body", body));
    }

    private static Map<String, Object> read(String name) {
        Path report = Path.of("target", "bootui-side-effects", "reports", name.replace("/", "_") + ".csv");
        try (InputStream in = Files.newInputStream(report)) {
            return Map.of("bytes", in.readAllBytes().length);
        } catch (NoSuchFileException ex) {
            return Map.of("bytes", 0);
        } catch (IOException ex) {
            return Map.of("error", ex.getClass().getSimpleName());
        }
    }

    private int port() {
        return Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
    }
}
