package io.github.jdubois.bootui.autoconfigure.web;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVC wiring tests for {@link VulnerabilitiesController}. The scan/parsing logic lives in
 * {@code OsvVulnerabilityScannerTests}; here we only assert routing/caching and that both routes feed the
 * shared {@link DismissedRulesStore} through {@link DependencyReports#applyDismissals} before serialization,
 * mirroring {@code ArchitectureControllerTests}.
 */
class VulnerabilitiesControllerTests {

    private static DependencyDto dependency(String groupId, String artifactId, String version) {
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(groupId, artifactId, version, packageName, "test", 0, "NONE", List.of());
    }

    private static DependencyDto vulnerableDependency(
            String groupId, String artifactId, String version, String vulnerabilityId, String severity) {
        String packageName = groupId + ":" + artifactId;
        List<DependencyVulnerabilityDto> vulnerabilities = List.of(new DependencyVulnerabilityDto(
                vulnerabilityId, null, null, severity, null, List.of(), List.of(), List.of()));
        return new DependencyDto(
                groupId, artifactId, version, packageName, "test", vulnerabilities.size(), severity, vulnerabilities);
    }

    private static DismissedRulesStore emptyDismissedRulesStore() {
        DismissedRulesStore store = mock(DismissedRulesStore.class);
        when(store.load()).thenReturn(Set.of());
        return store;
    }

    @Test
    void dependenciesReturnsClasspathInventoryWithoutScanning() throws Exception {
        MockMvc mvc = standaloneSetup(new VulnerabilitiesController(
                        new BootUiProperties(),
                        () -> List.of(dependency("org.example", "sample", "1.0.0")),
                        dependencies -> DependencyReports.report(true, "SCANNED", "unused", 1L, 1, dependencies),
                        emptyDismissedRulesStore()))
                .build();

        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanningEnabled").value(true))
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.dependencies[0].packageName").value("org.example:sample"))
                .andExpect(jsonPath("$.dependencies[0].vulnerabilityCount").value(0));
    }

    @Test
    void scanUsesScannerWhenEnabled() throws Exception {
        MockMvc mvc = standaloneSetup(new VulnerabilitiesController(
                        new BootUiProperties(),
                        () -> List.of(dependency("org.example", "sample", "1.0.0")),
                        dependencies -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, dependencies),
                        emptyDismissedRulesStore()))
                .build();

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.scan.message").value("done"))
                .andExpect(jsonPath("$.scan.packagesScanned").value(1));
    }

    @Test
    void scanReportsDisabledWhenOsvIsDisabled() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getVulnerabilities().setOsvEnabled(false);
        MockMvc mvc = standaloneSetup(new VulnerabilitiesController(
                        properties,
                        () -> List.of(dependency("org.example", "sample", "1.0.0")),
                        dependencies -> DependencyReports.report(true, "SCANNED", "unused", 1L, 1, dependencies),
                        emptyDismissedRulesStore()))
                .build();

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanningEnabled").value(false))
                .andExpect(jsonPath("$.scan.status").value("DISABLED"))
                .andExpect(jsonPath("$.dependencies[*].packageName", contains("org.example:sample")));
    }

    @Test
    void dependenciesReturnsLastScanReportAfterScan() throws Exception {
        MockMvc mvc = standaloneSetup(new VulnerabilitiesController(
                        new BootUiProperties(),
                        () -> List.of(dependency("org.example", "sample", "1.0.0")),
                        dependencies -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, dependencies),
                        emptyDismissedRulesStore()))
                .build();

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"));

        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.scan.message").value("done"));
    }

    @Test
    void dependenciesAppliesDismissalsFromTheStoreToTheCachedReport() throws Exception {
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        String key = DependencyReports.dismissalKey("GHSA-DISMISSED", "org.example:sample");
        when(dismissedRules.load()).thenReturn(Set.of(key));
        DependencyDto vulnerable = vulnerableDependency("org.example", "sample", "1.0.0", "GHSA-DISMISSED", "CRITICAL");
        VulnerabilitiesController controller = new VulnerabilitiesController(
                new BootUiProperties(),
                () -> List.of(vulnerable),
                dependencies -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, List.of(vulnerable)),
                dismissedRules);
        MockMvc mvc = standaloneSetup(controller).build();
        // Populate the cached lastScanReport so GET serves it (mirroring dependenciesReturnsLastScanReportAfterScan).
        mvc.perform(post("/bootui/api/vulnerabilities/scan")).andExpect(status().isOk());

        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies[0].vulnerabilities[0].dismissed")
                        .value(true))
                .andExpect(jsonPath("$.dependencies[0].vulnerabilityCount").value(0))
                .andExpect(jsonPath("$.vulnerable").value(0));
        verify(dismissedRules, atLeastOnce()).load();
    }

    @Test
    void scanAppliesDismissalsFromTheStoreToTheFreshReport() throws Exception {
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        String key = DependencyReports.dismissalKey("GHSA-DISMISSED", "org.example:sample");
        when(dismissedRules.load()).thenReturn(Set.of(key));
        DependencyDto vulnerable = vulnerableDependency("org.example", "sample", "1.0.0", "GHSA-DISMISSED", "HIGH");
        VulnerabilitiesController controller = new VulnerabilitiesController(
                new BootUiProperties(),
                () -> List.of(vulnerable),
                dependencies -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, List.of(vulnerable)),
                dismissedRules);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies[0].vulnerabilities[0].dismissed")
                        .value(true))
                .andExpect(jsonPath("$.dependencies[0].vulnerabilityCount").value(0));
    }
}
