package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree;
import io.github.jdubois.bootui.autoconfigure.sourcetree.ProjectSourceTree.Coordinates;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

class CracControllerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.crac.fixtures";
    private static final Coordinates COORDS = new Coordinates("com.example", "demo");

    private MockMvc mvc(ProjectSourceTree sourceTree) {
        CracReadinessScanner scanner = CracReadinessScanner.usingClasspath(
                () -> List.of(FIXTURES), CracRuntimeInventory::empty, Clock.systemUTC());
        CracRuntimeStatusCollector collector =
                new CracRuntimeStatusCollector(new MockEnvironment(), List::of, className -> false);
        return standaloneSetup(new CracController(scanner, collector, sourceTree))
                .build();
    }

    private MockMvc mvc() {
        return mvc(jarSourceTree());
    }

    @Test
    void getReturnsRuntimeStatusAndNotScannedReport() throws Exception {
        mvc().perform(get("/bootui/api/crac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localOnly").value(true))
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.runtime.cracApiPresent").value(false))
                .andExpect(jsonPath("$.findings.length()").value(0));
    }

    @Test
    void passiveGetUsesCachedResourceEvidenceWithoutCollectingAgain() throws Exception {
        AtomicInteger collections = new AtomicInteger();
        CracReadinessScanner scanner = CracReadinessScanner.usingClasspath(
                () -> List.of(FIXTURES),
                () -> {
                    collections.incrementAndGet();
                    return new CracRuntimeInventory(List.of("unverifiedPool : javax.sql.DataSource"));
                },
                Clock.systemUTC());
        ApplicationContext context = mock(ApplicationContext.class);
        MockMvc mvc = standaloneSetup(new CracController(
                        scanner, context, new MockEnvironment(), new BootUiExposure(new BootUiProperties())))
                .build();

        mvc.perform(get("/bootui/api/crac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.runtime.restoreCaveats",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("inventory is unavailable"))));
        mvc.perform(get("/bootui/api/crac")).andExpect(status().isOk());
        assertThat(collections).hasValue(0);

        mvc.perform(post("/bootui/api/crac/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.runtime.restoreCaveats",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("unverifiedPool"))));
        assertThat(collections).hasValue(1);
        mvc.perform(get("/bootui/api/crac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.runtime.restoreCaveats",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("unverifiedPool"))));
        mvc.perform(get("/bootui/api/crac")).andExpect(status().isOk());
        assertThat(collections).hasValue(1);
        verify(context, never()).getBeanNamesForType(any(Class.class), eq(false), eq(false));
    }

    @Test
    void scanRunsReadinessChecksAndReturnsFindings() throws Exception {
        mvc().perform(post("/bootui/api/crac/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.checksRun").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.findingsFound").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.runtime").exists());
    }

    @Test
    void reportExposesGeneratedContainerAssets(@TempDir Path projectRoot) throws Exception {
        mvc(sourceTree(projectRoot))
                .perform(get("/bootui/api/crac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedFiles.length()").value(2))
                .andExpect(jsonPath("$.generatedFiles[0].name").value("Dockerfile-crac"))
                .andExpect(jsonPath("$.generatedFiles[0].installable").value(true))
                .andExpect(jsonPath("$.generatedFiles[0].installPath").value("Dockerfile-crac"))
                .andExpect(jsonPath("$.generatedFiles[0].content")
                        .value(org.hamcrest.Matchers.containsString("bellsoft/liberica-runtime-container")))
                .andExpect(jsonPath("$.generatedFiles[1].name").value("checkpoint-and-run.sh"))
                .andExpect(jsonPath("$.generatedFiles[1].installable").value(true))
                .andExpect(jsonPath("$.generatedFiles[1].content")
                        .value(org.hamcrest.Matchers.containsString("CRaCCheckpointTo")));
    }

    @Test
    void dockerfileEndpointReturnsTailoredContent(@TempDir Path projectRoot) throws Exception {
        mvc(sourceTree(projectRoot))
                .perform(get("/bootui/api/crac/dockerfile"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FROM eclipse-temurin")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("generated by BootUI")));
    }

    @Test
    void entrypointEndpointReturnsScript(@TempDir Path projectRoot) throws Exception {
        mvc(sourceTree(projectRoot))
                .perform(get("/bootui/api/crac/entrypoint"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("#!/bin/sh")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("generated by BootUI")));
    }

    @Test
    void installAllWritesBothAssetsAndReturnsOk(@TempDir Path projectRoot) throws Exception {
        mvc(sourceTree(projectRoot))
                .perform(post("/bootui/api/crac/install/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(true))
                .andExpect(jsonPath("$.status").value("WRITTEN"))
                .andExpect(jsonPath("$.dockerfile.status").value("WRITTEN"))
                .andExpect(jsonPath("$.entrypoint.status").value("WRITTEN"));
        assertGeneratedFile(projectRoot.resolve("Dockerfile-crac"));
        assertGeneratedFile(projectRoot.resolve("checkpoint-and-run.sh"));
    }

    @Test
    void installDockerfileReturnsUnprocessableWhenRunningFromJar() throws Exception {
        mvc(jarSourceTree())
                .perform(post("/bootui/api/crac/dockerfile/install"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }

    @Test
    void installAllReportsMostSevereOutcome(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("Dockerfile-crac"), "FROM scratch\n", StandardCharsets.UTF_8);
        mvc(sourceTree(projectRoot))
                .perform(post("/bootui/api/crac/install/all"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.installed").value(false))
                .andExpect(jsonPath("$.status").value("EXISTS"))
                .andExpect(jsonPath("$.dockerfile.status").value("EXISTS"))
                .andExpect(jsonPath("$.entrypoint.status").value("WRITTEN"));
    }

    private static void assertGeneratedFile(Path file) throws IOException {
        org.assertj.core.api.Assertions.assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("generated by BootUI");
    }

    private ProjectSourceTree sourceTree(Path projectRoot) {
        try {
            Path classes = Files.createDirectories(projectRoot.resolve("target/classes"));
            return new ProjectSourceTree(
                    () -> projectRoot, () -> Optional.of(toUrl(classes)), () -> Optional.of(COORDS));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ProjectSourceTree jarSourceTree() {
        return new ProjectSourceTree(
                () -> Path.of(System.getProperty("user.dir", ".")),
                () -> Optional.of(toUrl(Path.of("/tmp/app.jar"))),
                () -> Optional.of(COORDS));
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
