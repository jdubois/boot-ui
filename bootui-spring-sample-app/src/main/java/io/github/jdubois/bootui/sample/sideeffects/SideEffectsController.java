package io.github.jdubois.bootui.sample.sideeffects;

import io.github.jdubois.bootui.sample.catalog.ProductSummary;
import io.github.jdubois.bootui.sample.catalog.SampleCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * M5-5d: {@code GET /api/side-effects/report} writes a report in the working directory, outside the temporary directory,
 * and reads a system property ({@link ReportWriter}); its counterexamples {@code /scratch} and {@code /log} write a
 * temporary file and a JDK logging handler's file.
 */
@RestController
@RequestMapping("/api/side-effects")
public class SideEffectsController {

    private final JavaVersionReporter reporter;
    private final LicenseSdkClient licenses;
    private final RestClient.Builder restClients;
    private final Environment environment;
    private final ReportWriter reports;
    private final SampleCatalog catalog;
    private final BenchmarkIo benchmarkIo;

    public SideEffectsController(
            JavaVersionReporter reporter,
            LicenseSdkClient licenses,
            RestClient.Builder restClients,
            Environment environment,
            ReportWriter reports,
            SampleCatalog catalog,
            BenchmarkIo benchmarkIo) {
        this.reporter = reporter;
        this.licenses = licenses;
        this.restClients = restClients;
        this.environment = environment;
        this.reports = reports;
        this.catalog = catalog;
        this.benchmarkIo = benchmarkIo;
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

    /**
     * The agent overhead benchmark's I/O route (M5-12): the product search of {@code GET /api/sample/product-search},
     * plus one outbound connect and one file read ({@link BenchmarkIo}).
     */
    @GetMapping("/benchmark-io")
    public List<ProductSummary> benchmarkIo(@RequestParam(name = "term", defaultValue = "console") String term) {
        benchmarkIo.touch();
        return catalog.searchProducts(term);
    }

    /**
     * The agent overhead benchmark's security-sinks route (M5-6b): two query parameters, the product search's one SQL
     * statement, and one file read, so request-value matching is measured where it pushes values and checks sinks.
     */
    @GetMapping("/benchmark-sinks")
    public List<ProductSummary> benchmarkSinks(
            @RequestParam(name = "term", defaultValue = "console") String term,
            @RequestParam(name = "tag", defaultValue = "sample") String tag) {
        benchmarkIo.read();
        return catalog.searchProducts(term);
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
