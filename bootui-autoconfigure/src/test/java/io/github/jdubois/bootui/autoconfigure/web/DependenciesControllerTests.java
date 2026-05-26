package io.github.jdubois.bootui.autoconfigure.web;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.DependencyDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class DependenciesControllerTests {

    @Test
    void dependenciesReturnsClasspathInventoryWithoutScanning() throws Exception {
        MockMvc mvc = standaloneSetup(new DependenciesController(
                new BootUiProperties(),
                () -> List.of(dependency("org.example", "sample", "1.0.0")),
                dependencies -> OsvVulnerabilityScanner.report(true, "SCANNED", "unused", 1L, 1, dependencies)))
                .build();

        mvc.perform(get("/bootui/api/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanningEnabled").value(true))
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.dependencies[0].packageName").value("org.example:sample"))
                .andExpect(jsonPath("$.dependencies[0].vulnerabilityCount").value(0));
    }

    @Test
    void scanUsesScannerWhenEnabled() throws Exception {
        MockMvc mvc = standaloneSetup(new DependenciesController(
                new BootUiProperties(),
                () -> List.of(dependency("org.example", "sample", "1.0.0")),
                dependencies -> OsvVulnerabilityScanner.report(true, "SCANNED", "done", 1L, 1, dependencies)))
                .build();

        mvc.perform(post("/bootui/api/dependencies/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.scan.message").value("done"))
                .andExpect(jsonPath("$.scan.packagesScanned").value(1));
    }

    @Test
    void scanReportsDisabledWhenOsvIsDisabled() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDependencies().setOsvEnabled(false);
        MockMvc mvc = standaloneSetup(new DependenciesController(
                properties,
                () -> List.of(dependency("org.example", "sample", "1.0.0")),
                dependencies -> OsvVulnerabilityScanner.report(true, "SCANNED", "unused", 1L, 1, dependencies)))
                .build();

        mvc.perform(post("/bootui/api/dependencies/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanningEnabled").value(false))
                .andExpect(jsonPath("$.scan.status").value("DISABLED"))
                .andExpect(jsonPath("$.dependencies[*].packageName", contains("org.example:sample")));
    }

    private static DependencyDto dependency(String groupId, String artifactId, String version) {
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(groupId, artifactId, version, packageName, "test", 0, "NONE", List.of());
    }
}
