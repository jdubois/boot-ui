package io.github.jdubois.bootui.sample.sideeffects;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Side Effects' seeded process ({@code docs/PLAN-v2.md} §5.16, M5-5a): with the BootUI agent, {@code GET
 * /api/side-effects/java-version} shows in the Processes table as a {@code java} process started by
 * {@link JavaVersionReporter#version()}, with its exit status, and never its arguments; the counterexample {@code GET
 * /api/side-effects/runtime-version} answers the same from the running JVM and starts no process.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;

    public SideEffectsController(JavaVersionReporter reporter) {
        this.reporter = reporter;
    }

    @GetMapping("/java-version")
    public Map<String, String> javaVersion() {
        return Map.of("version", reporter.version());
    }

    @GetMapping("/runtime-version")
    public Map<String, String> runtimeVersion() {
        return Map.of("version", reporter.runtimeVersion());
    }
}
