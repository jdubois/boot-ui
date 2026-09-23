package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.DependencyAssessmentDto;
import io.github.jdubois.bootui.core.dto.DependencyCoverageDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports;
import io.github.jdubois.bootui.engine.vulnerabilities.VulnerabilityScanner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                "test",
                0,
                "NONE",
                List.of(),
                DependencyAssessmentDto.unknown());
    }

    private static DependencyDto vulnerableDependency(
            String groupId, String artifactId, String version, String vulnerabilityId, String severity) {
        String packageName = groupId + ":" + artifactId;
        List<DependencyVulnerabilityDto> vulnerabilities = List.of(new DependencyVulnerabilityDto(
                vulnerabilityId, null, null, severity, null, List.of(), List.of(), List.of()));
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                "test",
                vulnerabilities.size(),
                severity,
                vulnerabilities,
                DependencyAssessmentDto.unknown());
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
                        inventory ->
                                DependencyReports.report(true, "SCANNED", "unused", 1L, 1, inventory.dependencies()),
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
                        inventory -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, inventory.dependencies()),
                        emptyDismissedRulesStore()))
                .build();

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.scan.message").value("done"))
                .andExpect(jsonPath("$.scan.packagesScanned").value(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesArchiveCoverageAcrossInventoryScanAndCachedReads(boolean scanningEnabled) throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getVulnerabilities().setOsvEnabled(scanningEnabled);
        DependencyInventory inventory = new DependencyInventory(
                List.of(
                        dependency("com.example", "resolved", "1.0"),
                        dependency("com.example", "sbom-only", "1.0"),
                        dependency("com.example", "another-sbom-component", "1.0")),
                DependencyCoverageDto.of(3, 1, List.of("mystery-1.0.jar"), 1, List.of("orders.jar")));
        Map<String, Object> coverage = Map.ofEntries(
                Map.entry("status", "INCOMPLETE"),
                Map.entry("archivesFound", 3),
                Map.entry("archivesIdentified", 1),
                Map.entry("archivesUnidentified", 1),
                Map.entry("unidentifiedArchives", List.of("mystery-1.0.jar")),
                Map.entry("unidentifiedArchivesTruncated", false),
                Map.entry("archivesFirstParty", 1),
                Map.entry("firstPartyArchives", List.of("orders.jar")),
                Map.entry("firstPartyArchivesTruncated", false));
        DependencyProvider provider = mock(DependencyProvider.class);
        when(provider.inventory()).thenReturn(inventory);
        VulnerabilityScanner scanner = mock(VulnerabilityScanner.class);
        when(scanner.scan(inventory))
                .thenReturn(DependencyReports.report(
                        true, "SCANNED", "done", 1L, 3, 0, inventory.dependencies(), inventory.coverage()));
        MockMvc mvc = standaloneSetup(
                        new VulnerabilitiesController(properties, provider, scanner, emptyDismissedRulesStore()))
                .build();

        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.coverage").value(equalTo(coverage)));
        verifyNoInteractions(scanner);

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.scan.status").value(scanningEnabled ? "SCANNED" : "DISABLED"))
                .andExpect(jsonPath("$.coverage").value(equalTo(coverage)));
        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.scan.status").value(scanningEnabled ? "SCANNED" : "NOT_SCANNED"))
                .andExpect(jsonPath("$.coverage").value(equalTo(coverage)));
        if (scanningEnabled) {
            verify(scanner).scan(inventory);
        } else {
            verifyNoInteractions(scanner);
        }
    }

    @Test
    void duplicateScanReturnsCanonicalConflictWithoutReplacingCachedReport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger scans = new AtomicInteger();
        VulnerabilitiesController controller = new VulnerabilitiesController(
                new BootUiProperties(),
                () -> List.of(dependency("org.example", "sample", "1.0.0")),
                inventory -> {
                    scans.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for test latch");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    return DependencyReports.report(true, "SCANNED", "done", 1L, 1, inventory.dependencies());
                },
                emptyDismissedRulesStore());
        MockMvc mvc = standaloneSetup(controller)
                .setControllerAdvice(new ActionBusyExceptionHandler())
                .build();
        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        Thread winner = new Thread(() -> {
            try {
                mvc.perform(post("/bootui/api/vulnerabilities/scan")).andExpect(status().isOk());
            } catch (Throwable failure) {
                winnerFailure.set(failure);
            }
        });
        winner.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"));
        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BootUI action already in progress"))
                .andExpect(jsonPath("$.operation").value("vulnerabilities.scan"))
                .andExpect(jsonPath("$.activeOperation").value("vulnerabilities.scan"))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Operation 'vulnerabilities.scan' cannot start while 'vulnerabilities.scan' is in progress."));
        assertThat(scans).hasValue(1);

        release.countDown();
        winner.join(5000);
        assertThat(winnerFailure.get()).isNull();
        mvc.perform(get("/bootui/api/vulnerabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"));
    }

    @Test
    void scanReportsDisabledWhenOsvIsDisabled() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getVulnerabilities().setOsvEnabled(false);
        MockMvc mvc = standaloneSetup(new VulnerabilitiesController(
                        properties,
                        () -> List.of(dependency("org.example", "sample", "1.0.0")),
                        inventory ->
                                DependencyReports.report(true, "SCANNED", "unused", 1L, 1, inventory.dependencies()),
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
                        inventory -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, inventory.dependencies()),
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
                inventory -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, List.of(vulnerable)),
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
                inventory -> DependencyReports.report(true, "SCANNED", "done", 1L, 1, List.of(vulnerable)),
                dismissedRules);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/bootui/api/vulnerabilities/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies[0].vulnerabilities[0].dismissed")
                        .value(true))
                .andExpect(jsonPath("$.dependencies[0].vulnerabilityCount").value(0));
    }
}
