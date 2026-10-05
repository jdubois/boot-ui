package io.github.jdubois.bootui.sample.sideeffects;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Side Effects' seeds ({@code docs/PLAN-v2.md} §5.16). M5-5a: with the BootUI agent, {@code GET
 * /api/side-effects/java-version} shows in the Processes table as a {@code java} process started by
 * {@link JavaVersionReporter#version()}, with its exit status, and never its arguments; the counterexample {@code GET
 * /api/side-effects/runtime-version} answers the same from the running JVM and starts no process. M5-5b: {@code GET
 * /api/side-effects/sdk-call} connects to this application through an SDK's own socket ({@link LicenseSdkClient}), a
 * Network row <b>not captured by any panel</b>; the counterexample {@code GET /api/side-effects/rest-call} calls the
 * same endpoint through a recorded {@link RestClient}, whose connection REST Client Trace captures.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;
    private final LicenseSdkClient licenses;
    private final RestClient.Builder restClients;
    private final Environment environment;

    public SideEffectsController(
            JavaVersionReporter reporter,
            LicenseSdkClient licenses,
            RestClient.Builder restClients,
            Environment environment) {
        this.reporter = reporter;
        this.licenses = licenses;
        this.restClients = restClients;
        this.environment = environment;
    }

    @GetMapping("/java-version")
    public Map<String, String> javaVersion() {
        return Map.of("version", reporter.version());
    }

    @GetMapping("/runtime-version")
    public Map<String, String> runtimeVersion() {
        return Map.of("version", reporter.runtimeVersion());
    }

    @GetMapping("/sdk-call")
    public Map<String, String> sdkCall() {
        return Map.of("status", licenses.check(port()));
    }

    @GetMapping("/rest-call")
    public Map<String, String> restCall() {
        String body = restClients
                .build()
                .get()
                .uri("http://localhost:" + port() + "/api/side-effects/runtime-version")
                .retrieve()
                .body(String.class);
        return Map.of("body", body == null ? "" : body);
    }

    private int port() {
        return Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
    }
}
