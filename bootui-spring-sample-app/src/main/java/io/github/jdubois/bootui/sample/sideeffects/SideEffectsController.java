package io.github.jdubois.bootui.sample.sideeffects;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Side Effects' seeded process ({@code docs/PLAN-v2.md} §5.16, M5-5a), file, and property read (M5-5d: {@code GET
 * /api/side-effects/report}, with {@code /scratch} and {@code /log} as counterexamples, see {@link ReportWriter}): with the BootUI agent, {@code GET
 * /api/side-effects/java-version} shows in the Processes table as a {@code java} process started by
 * {@link JavaVersionReporter#version()}, with its exit status, and never its arguments; the counterexample {@code GET
 * /api/side-effects/runtime-version} answers the same from the running JVM and starts no process.
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

    /** Writes a report outside the temporary directory and reads a system property (M5-5d's seed). */
    @GetMapping("/report")
    public Map<String, String> report() {
        return Map.of("report", reports.writeReport());
    }

    /** The counterexample: a temporary file, written and deleted. */
    @GetMapping("/scratch")
    public Map<String, String> scratch() {
        return Map.of("scratch", reports.scratch());
    }

    /** The counterexample: a JDK logging handler's file, grouped apart as logging. */
    @GetMapping("/log")
    public Map<String, String> log() {
        return Map.of("log", reports.log());
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
