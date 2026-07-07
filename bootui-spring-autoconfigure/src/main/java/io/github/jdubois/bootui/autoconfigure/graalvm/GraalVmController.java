package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectBuildSystem;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree.InstallOutcome;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree.Resolution;
import io.github.jdubois.bootui.core.dto.GraalVmDockerfileDto;
import io.github.jdubois.bootui.core.dto.GraalVmInstallAllResultDto;
import io.github.jdubois.bootui.core.dto.GraalVmInstallResultDto;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import io.github.jdubois.bootui.core.dto.GraalVmScanProgressDto;
import io.github.jdubois.bootui.engine.graalvm.GraalVmMetadataGenerator;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner.GraalVmScanResult;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * last scan; {@code POST /install} writes that same scaffold directly into the host application's
 * source tree when it is detectably running from an exploded build rather than a packaged jar;
 * {@code GET /dockerfile} downloads a native-image {@code Dockerfile-native} tailored to the host
 * application; {@code POST /dockerfile/install} writes it to the project root under the same
 * exploded-build constraint; {@code POST /install/all} writes both artifacts in a single action.</p>
 */
@RestController
@RequestMapping("/bootui/api/graalvm")
public class GraalVmController {

    private final GraalVmReadinessScanner scanner;
    private final GraalVmMetadataGenerator metadataGenerator;
    private final GraalVmDockerfileGenerator dockerfileGenerator;
    private final GraalVmSourceLayout sourceLayout;

    private volatile GraalVmScanResult lastResult;

    @Autowired
    public GraalVmController(GraalVmReadinessScanner scanner, ApplicationContext applicationContext) {
        this(
                scanner,
                new GraalVmMetadataGenerator(),
                new GraalVmSourceLayout(ProjectSourceTree.forApplication(applicationContext)));
    }

    GraalVmController(
            GraalVmReadinessScanner scanner,
            GraalVmMetadataGenerator metadataGenerator,
            GraalVmSourceLayout sourceLayout) {
        this.scanner = scanner;
        this.metadataGenerator = metadataGenerator;
        this.dockerfileGenerator = new GraalVmDockerfileGenerator();
        this.sourceLayout = sourceLayout;
        this.lastResult = scanner.initialResult();
    }

    @GetMapping
    public GraalVmReadinessReport graalvm() {
        return augment(lastResult.report());
    }

    @GetMapping("/scan/progress")
    public GraalVmScanProgressDto progress() {
        return scanner.progress();
    }

    @PostMapping("/scan/cancel")
    public GraalVmScanProgressDto scanCancel() {
        scanner.cancelDependencyScan();
        return scanner.progress();
    }

    @PostMapping("/scan")
    public GraalVmReadinessReport scan(
            @RequestParam(name = "includeDependencies", defaultValue = "true") boolean includeDependencies) {
        GraalVmScanResult result = scanner.scan(includeDependencies);
        lastResult = result;
        return augment(result.report());
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

    @PostMapping("/install")
    public ResponseEntity<GraalVmInstallResultDto> install() {
        String json = metadataGenerator.generate(lastResult.metadata());
        return toResponse(sourceLayout.install(json));
    }

    @PostMapping("/install/all")
    public ResponseEntity<GraalVmInstallAllResultDto> installAll() {
        InstallOutcome metadataOutcome = sourceLayout.install(metadataGenerator.generate(lastResult.metadata()));
        InstallOutcome dockerfileOutcome = sourceLayout.installDockerfile(generateDockerfile());
        String status = mostSevere(metadataOutcome.status(), dockerfileOutcome.status());
        boolean installed = "WRITTEN".equals(metadataOutcome.status()) && "WRITTEN".equals(dockerfileOutcome.status());
        String message = installed
                ? "Wrote reachability-metadata.json and Dockerfile-native into the project source tree."
                : "Finished writing the GraalVM artifacts \u2014 review the per-file results below.";
        GraalVmInstallAllResultDto body = new GraalVmInstallAllResultDto(
                installed, status, message, toDto(metadataOutcome), toDto(dockerfileOutcome));
        return ResponseEntity.status(httpStatusFor(status)).body(body);
    }

    @GetMapping(value = "/dockerfile", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> dockerfile() {
        byte[] body = generateDockerfile().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Dockerfile-native\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    @PostMapping("/dockerfile/install")
    public ResponseEntity<GraalVmInstallResultDto> installDockerfile() {
        return toResponse(sourceLayout.installDockerfile(generateDockerfile()));
    }

    /** Generates the Dockerfile-native tailored to the host application's artifact and build system. */
    private String generateDockerfile() {
        return dockerfileGenerator.generate(
                sourceLayout.artifactName(), ProjectBuildSystem.detect(sourceLayout.projectRoot()));
    }

    private ResponseEntity<GraalVmInstallResultDto> toResponse(InstallOutcome outcome) {
        return ResponseEntity.status(httpStatusFor(outcome.status())).body(toDto(outcome));
    }

    private static GraalVmInstallResultDto toDto(InstallOutcome outcome) {
        return new GraalVmInstallResultDto(
                "WRITTEN".equals(outcome.status()), outcome.status(), outcome.message(), outcome.path());
    }

    private static HttpStatus httpStatusFor(String status) {
        return switch (status) {
            case "WRITTEN" -> HttpStatus.OK;
            case "EXISTS" -> HttpStatus.CONFLICT;
            case "UNAVAILABLE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** Returns the more severe of two install statuses, so a combined write reflects its worst outcome. */
    private static String mostSevere(String first, String second) {
        return severityRank(first) >= severityRank(second) ? first : second;
    }

    private static int severityRank(String status) {
        return switch (status) {
            case "ERROR" -> 3;
            case "UNAVAILABLE" -> 2;
            case "EXISTS" -> 1;
            default -> 0;
        };
    }

    private GraalVmReadinessReport augment(GraalVmReadinessReport report) {
        Resolution resolution = sourceLayout.resolve();
        Resolution dockerResolution = sourceLayout.resolveDockerfile();
        GraalVmDockerfileDto dockerfile = new GraalVmDockerfileDto(
                generateDockerfile(),
                dockerResolution.installable(),
                dockerResolution.installable() ? dockerResolution.displayPath() : dockerResolution.reason());
        return report.withInstallTarget(
                        resolution.installable(),
                        resolution.installable() ? resolution.displayPath() : resolution.reason(),
                        sourceLayout.metadataDirectory())
                .withDockerfile(dockerfile);
    }
}
