package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.autoconfigure.crac.CracDockerfileGenerator.BuildTool;
import io.github.jdubois.bootui.autoconfigure.crac.CracReadinessScanner.CracScanResult;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree.InstallOutcome;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree.Resolution;
import io.github.jdubois.bootui.core.dto.CracGeneratedFileDto;
import io.github.jdubois.bootui.core.dto.CracInstallAllResultDto;
import io.github.jdubois.bootui.core.dto.CracInstallResultDto;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the local CRaC (Coordinated Restore at Checkpoint) panel.
 *
 * <p>{@code GET} returns the live runtime status plus the last readiness report (initially "not
 * scanned"); {@code POST /scan} runs the curated readiness checks and caches the result. The runtime
 * status is recomputed on every request so it always reflects the current process, while the
 * heuristic scan is only refreshed by the explicit action.</p>
 *
 * <p>Both responses are augmented with generated container assets: {@code GET /dockerfile} and
 * {@code GET /entrypoint} download a tailored {@code Dockerfile-crac} and its
 * {@code checkpoint-and-run.sh} entrypoint; {@code POST /dockerfile/install},
 * {@code POST /entrypoint/install}, and {@code POST /install/all} write them directly into the host
 * application's project directory when it is detectably running from an exploded build rather than a
 * packaged jar.</p>
 */
@RestController
@RequestMapping("/bootui/api/crac")
public class CracController {

    private static final String DOCKERFILE_FILE = "Dockerfile-crac";
    private static final String ENTRYPOINT_FILE = "checkpoint-and-run.sh";

    private final CracReadinessScanner scanner;
    private final CracRuntimeStatusCollector runtimeStatusCollector;
    private final ProjectSourceTree sourceTree;
    private final CracDockerfileGenerator dockerfileGenerator;

    private volatile CracScanResult lastResult;

    @Autowired
    public CracController(ApplicationContext applicationContext, Environment environment) {
        this(
                new CracReadinessScanner(
                        () -> CracPackages.detect(applicationContext),
                        new ClassFileCracImporter(),
                        Clock.systemUTC(),
                        () -> CracRuntimeInventoryCollector.collect(applicationContext)),
                new CracRuntimeStatusCollector(
                        environment, () -> CracRuntimeInventoryCollector.collect(applicationContext)),
                ProjectSourceTree.forApplication(applicationContext));
    }

    CracController(
            CracReadinessScanner scanner,
            CracRuntimeStatusCollector runtimeStatusCollector,
            ProjectSourceTree sourceTree) {
        this.scanner = scanner;
        this.runtimeStatusCollector = runtimeStatusCollector;
        this.sourceTree = sourceTree;
        this.dockerfileGenerator = new CracDockerfileGenerator();
        this.lastResult = scanner.initialResult();
    }

    @GetMapping
    public CracReadinessReport crac() {
        return augment(scanner.report(lastResult, runtimeStatus()));
    }

    @PostMapping("/scan")
    public CracReadinessReport scan() {
        CracScanResult result = scanner.scan();
        lastResult = result;
        return augment(scanner.report(result, runtimeStatus()));
    }

    @GetMapping(value = "/dockerfile", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> dockerfile() {
        byte[] body = generateDockerfile().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Dockerfile-crac\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    @GetMapping(value = "/entrypoint", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> entrypoint() {
        byte[] body = dockerfileGenerator.generateEntrypoint().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"checkpoint-and-run.sh\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    @PostMapping("/dockerfile/install")
    public ResponseEntity<CracInstallResultDto> installDockerfile() {
        return toResponse(writeDockerfile());
    }

    @PostMapping("/entrypoint/install")
    public ResponseEntity<CracInstallResultDto> installEntrypoint() {
        return toResponse(writeEntrypoint());
    }

    @PostMapping("/install/all")
    public ResponseEntity<CracInstallAllResultDto> installAll() {
        InstallOutcome dockerfileOutcome = writeDockerfile();
        InstallOutcome entrypointOutcome = writeEntrypoint();
        String status = mostSevere(dockerfileOutcome.status(), entrypointOutcome.status());
        boolean installed =
                "WRITTEN".equals(dockerfileOutcome.status()) && "WRITTEN".equals(entrypointOutcome.status());
        String message = installed
                ? "Wrote Dockerfile-crac and checkpoint-and-run.sh into the project directory."
                : "Finished writing the CRaC container assets \u2014 review the per-file results below.";
        CracInstallAllResultDto body = new CracInstallAllResultDto(
                installed, status, message, toDto(dockerfileOutcome), toDto(entrypointOutcome));
        return ResponseEntity.status(httpStatusFor(status)).body(body);
    }

    private CracReadinessReport augment(CracReadinessReport report) {
        Resolution dockerResolution = sourceTree.resolveProjectRootFile(DOCKERFILE_FILE);
        Resolution entrypointResolution = sourceTree.resolveProjectRootFile(ENTRYPOINT_FILE);
        CracGeneratedFileDto dockerfile = new CracGeneratedFileDto(
                DOCKERFILE_FILE,
                generateDockerfile(),
                dockerResolution.installable(),
                dockerResolution.installable() ? dockerResolution.displayPath() : dockerResolution.reason());
        CracGeneratedFileDto entrypoint = new CracGeneratedFileDto(
                ENTRYPOINT_FILE,
                dockerfileGenerator.generateEntrypoint(),
                entrypointResolution.installable(),
                entrypointResolution.installable()
                        ? entrypointResolution.displayPath()
                        : entrypointResolution.reason());
        return report.withGeneratedFiles(List.of(dockerfile, entrypoint));
    }

    /** Generates the Dockerfile-crac tailored to the host application's artifact and build system. */
    private String generateDockerfile() {
        BuildTool buildTool = CracDockerfileGenerator.detect(sourceTree.projectRoot());
        return dockerfileGenerator.generateDockerfile(sourceTree.artifactName(), buildTool);
    }

    private InstallOutcome writeDockerfile() {
        return sourceTree.write(
                sourceTree.resolveProjectRootFile(DOCKERFILE_FILE),
                generateDockerfile(),
                displayPath -> "A Dockerfile-crac already exists at " + displayPath
                        + " and was not generated by BootUI, so it was left untouched. "
                        + "Download the generated one and merge it by hand instead.",
                displayPath -> "Wrote the CRaC Dockerfile to " + displayPath
                        + ". Also write checkpoint-and-run.sh, then build with: "
                        + "docker build -f Dockerfile-crac -t app .",
                "Failed to write the Dockerfile: ");
    }

    private InstallOutcome writeEntrypoint() {
        return sourceTree.write(
                sourceTree.resolveProjectRootFile(ENTRYPOINT_FILE),
                dockerfileGenerator.generateEntrypoint(),
                displayPath -> "A checkpoint-and-run.sh already exists at " + displayPath
                        + " and was not generated by BootUI, so it was left untouched. "
                        + "Download the generated one and merge it by hand instead.",
                displayPath -> "Wrote the CRaC entrypoint to " + displayPath
                        + ". It is referenced by the generated Dockerfile-crac.",
                "Failed to write the entrypoint script: ");
    }

    private ResponseEntity<CracInstallResultDto> toResponse(InstallOutcome outcome) {
        return ResponseEntity.status(httpStatusFor(outcome.status())).body(toDto(outcome));
    }

    private static CracInstallResultDto toDto(InstallOutcome outcome) {
        return new CracInstallResultDto(
                "WRITTEN".equals(outcome.status()), outcome.status(), outcome.message(), outcome.path());
    }

    private static HttpStatus httpStatusFor(String status) {
        return switch (status) {
            case "WRITTEN" -> HttpStatus.OK;
            case "EXISTS" -> HttpStatus.CONFLICT;
            case "UNAVAILABLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
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

    private CracRuntimeStatusDto runtimeStatus() {
        return runtimeStatusCollector.collect();
    }
}
