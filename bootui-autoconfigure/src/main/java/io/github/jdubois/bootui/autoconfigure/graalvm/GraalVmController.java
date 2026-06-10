package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmReadinessScanner.GraalVmScanResult;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmSourceLayout.Coordinates;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmSourceLayout.InstallOutcome;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmSourceLayout.Resolution;
import io.github.jdubois.bootui.core.dto.GraalVmDockerfileDto;
import io.github.jdubois.bootui.core.dto.GraalVmInstallResultDto;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
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
 * exploded-build constraint.</p>
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
    public GraalVmController(ApplicationContext applicationContext) {
        this(
                new GraalVmReadinessScanner(
                        () -> GraalVmPackages.detect(applicationContext),
                        new ClassFileGraalVmImporter(),
                        new GraalVmDependencyScanner(),
                        Clock.systemUTC()),
                new GraalVmMetadataGenerator(),
                defaultSourceLayout(applicationContext));
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

    @GetMapping(value = "/dockerfile", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> dockerfile() {
        byte[] body = dockerfileGenerator.generate(sourceLayout.artifactName()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Dockerfile-native\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    @PostMapping("/dockerfile/install")
    public ResponseEntity<GraalVmInstallResultDto> installDockerfile() {
        String content = dockerfileGenerator.generate(sourceLayout.artifactName());
        return toResponse(sourceLayout.installDockerfile(content));
    }

    private ResponseEntity<GraalVmInstallResultDto> toResponse(InstallOutcome outcome) {
        GraalVmInstallResultDto body = new GraalVmInstallResultDto(
                "WRITTEN".equals(outcome.status()), outcome.status(), outcome.message(), outcome.path());
        HttpStatus status =
                switch (outcome.status()) {
                    case "WRITTEN" -> HttpStatus.OK;
                    case "EXISTS" -> HttpStatus.CONFLICT;
                    case "UNAVAILABLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        return ResponseEntity.status(status).body(body);
    }

    private GraalVmReadinessReport augment(GraalVmReadinessReport report) {
        Resolution resolution = sourceLayout.resolve();
        Resolution dockerResolution = sourceLayout.resolveDockerfile();
        GraalVmDockerfileDto dockerfile = new GraalVmDockerfileDto(
                dockerfileGenerator.generate(sourceLayout.artifactName()),
                dockerResolution.installable(),
                dockerResolution.installable() ? dockerResolution.displayPath() : dockerResolution.reason());
        return report.withInstallTarget(
                        resolution.installable(),
                        resolution.installable() ? resolution.displayPath() : resolution.reason(),
                        sourceLayout.metadataDirectory())
                .withDockerfile(dockerfile);
    }

    private static GraalVmSourceLayout defaultSourceLayout(ApplicationContext applicationContext) {
        return new GraalVmSourceLayout(
                () -> Path.of(System.getProperty("user.dir", ".")),
                () -> applicationCodeSource(applicationContext),
                () -> coordinates(applicationContext));
    }

    private static Optional<URL> applicationCodeSource(ApplicationContext applicationContext) {
        try {
            Map<String, Object> beans = applicationContext.getBeansWithAnnotation(SpringBootApplication.class);
            for (Object bean : beans.values()) {
                Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
                ProtectionDomain protectionDomain = userClass.getProtectionDomain();
                if (protectionDomain == null) {
                    continue;
                }
                CodeSource codeSource = protectionDomain.getCodeSource();
                if (codeSource != null && codeSource.getLocation() != null) {
                    return Optional.of(codeSource.getLocation());
                }
            }
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<Coordinates> coordinates(ApplicationContext applicationContext) {
        try {
            BuildProperties buildProperties =
                    applicationContext.getBeanProvider(BuildProperties.class).getIfAvailable();
            if (buildProperties != null) {
                Coordinates fromBuild = new Coordinates(buildProperties.getGroup(), buildProperties.getArtifact());
                if (fromBuild.isValid()) {
                    return Optional.of(fromBuild);
                }
            }
        } catch (RuntimeException ex) {
            // Fall through to pom parsing.
        }
        return GraalVmSourceLayout.coordinatesFromPom(Path.of(System.getProperty("user.dir", ".")));
    }
}
