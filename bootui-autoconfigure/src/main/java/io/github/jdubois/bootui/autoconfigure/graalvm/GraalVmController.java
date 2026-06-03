package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmReadinessScanner.GraalVmScanResult;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the GraalVM native-image readiness panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} runs the
 * curated readiness checks (optionally surveying classpath dependencies) and caches the result;
 * {@code GET /metadata} downloads a {@code reachability-metadata.json} scaffold derived from the
 * last scan.</p>
 */
@RestController
@RequestMapping("/bootui/api/graalvm")
public class GraalVmController {

    private final GraalVmReadinessScanner scanner;
    private final GraalVmMetadataGenerator metadataGenerator;

    private volatile GraalVmScanResult lastResult;

    @Autowired
    public GraalVmController(ApplicationContext applicationContext) {
        this(
                new GraalVmReadinessScanner(
                        () -> GraalVmPackages.detect(applicationContext),
                        new ClassFileGraalVmImporter(),
                        new GraalVmDependencyScanner(),
                        Clock.systemUTC()),
                new GraalVmMetadataGenerator());
    }

    GraalVmController(GraalVmReadinessScanner scanner, GraalVmMetadataGenerator metadataGenerator) {
        this.scanner = scanner;
        this.metadataGenerator = metadataGenerator;
        this.lastResult = scanner.initialResult();
    }

    @GetMapping
    public GraalVmReadinessReport graalvm() {
        return lastResult.report();
    }

    @PostMapping("/scan")
    public GraalVmReadinessReport scan(
            @RequestParam(name = "includeDependencies", defaultValue = "true") boolean includeDependencies) {
        GraalVmScanResult result = scanner.scan(includeDependencies);
        lastResult = result;
        return result.report();
    }

    @GetMapping("/metadata")
    public ResponseEntity<byte[]> metadata() {
        String json = metadataGenerator.generate(lastResult.metadata());
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reachability-metadata.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
