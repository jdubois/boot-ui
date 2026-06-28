package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureScanStatusDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link ArchitectureController}. The scan logic lives in the engine
 * {@link ArchitectureScanner} (covered by {@code ArchitectureScannerTests}/{@code ArchitectureRulesTests}
 * in {@code bootui-engine}), so here we only assert that {@code GET} returns the cached report, that
 * {@code POST /scan} refreshes the cache, and that both routes feed the adapter's dismissed-rule ids
 * through {@link ArchitectureScanner#applyDismissals} before serialization.
 */
class ArchitectureControllerTests {

    private static ArchitectureReport report(String status, int violationsFound) {
        return new ArchitectureReport(
                true,
                "disclaimer",
                List.of("com.example.app"),
                10,
                5,
                violationsFound,
                List.of(),
                new ArchitectureScanStatusDto(
                        "BootUI ArchUnit hygiene", status, "message", null, 5, 10, violationsFound),
                List.of(),
                List.of());
    }

    @Test
    void getReturnsCachedInitialReportWithDismissalsApplied() throws Exception {
        ArchitectureScanner scanner = mock(ArchitectureScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        ArchitectureReport initial = report("NOT_SCANNED", 0);
        ArchitectureReport dismissedView = report("NOT_SCANNED", 0);
        when(scanner.initialReport()).thenReturn(initial);
        when(dismissedRules.load()).thenReturn(Set.of("ARCH-IGNORED"));
        when(scanner.applyDismissals(eq(initial), eq(Set.of("ARCH-IGNORED")))).thenReturn(dismissedView);

        MockMvc mvc = standaloneSetup(new ArchitectureController(scanner, dismissedRules))
                .build();

        mvc.perform(get("/bootui/api/architecture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"));
        verify(scanner).applyDismissals(initial, Set.of("ARCH-IGNORED"));
    }

    @Test
    void scanRefreshesCachedReportAndAppliesDismissals() throws Exception {
        ArchitectureScanner scanner = mock(ArchitectureScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        when(scanner.initialReport()).thenReturn(report("NOT_SCANNED", 0));
        ArchitectureReport scanned = report("SCANNED", 3);
        when(scanner.scan()).thenReturn(scanned);
        when(dismissedRules.load()).thenReturn(Set.of());
        when(scanner.applyDismissals(eq(scanned), any())).thenReturn(scanned);

        MockMvc mvc = standaloneSetup(new ArchitectureController(scanner, dismissedRules))
                .build();

        mvc.perform(post("/bootui/api/architecture/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.violationsFound").value(3));
        verify(scanner).scan();
    }
}
