package io.github.jdubois.bootui.autoconfigure.memory;

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

import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryScanStatusDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link MemoryController}. The collection and rule logic lives in the engine
 * {@link MemoryScanner} (covered by {@code MemoryScannerTests}/{@code MemoryRulesTests} in
 * {@code bootui-engine}), so here we only assert that {@code GET} returns the cached report, that
 * {@code POST /scan} refreshes the cache, and that both routes feed the adapter's dismissed-rule ids
 * through {@link MemoryScanner#applyDismissals} before serialization.
 */
class MemoryControllerTests {

    private static MemoryReport report(String status, int violationsFound) {
        return new MemoryReport(
                true,
                "disclaimer",
                31,
                violationsFound,
                null,
                List.of(),
                new MemoryScanStatusDto("BootUI Memory Advisor", status, "message", null, 31, violationsFound),
                List.of(),
                List.of());
    }

    @Test
    void getReturnsCachedInitialReportWithDismissalsApplied() throws Exception {
        MemoryScanner scanner = mock(MemoryScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        MemoryReport initial = report("NOT_SCANNED", 0);
        when(scanner.initialReport()).thenReturn(initial);
        when(dismissedRules.load()).thenReturn(Set.of("MEM-IGNORED"));
        when(scanner.applyDismissals(eq(initial), eq(Set.of("MEM-IGNORED")))).thenReturn(initial);

        MockMvc mvc =
                standaloneSetup(new MemoryController(scanner, dismissedRules)).build();

        mvc.perform(get("/bootui/api/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"));
        verify(scanner).applyDismissals(initial, Set.of("MEM-IGNORED"));
    }

    @Test
    void scanRefreshesCachedReportAndAppliesDismissals() throws Exception {
        MemoryScanner scanner = mock(MemoryScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        when(scanner.initialReport()).thenReturn(report("NOT_SCANNED", 0));
        MemoryReport scanned = report("SCANNED", 3);
        when(scanner.scan()).thenReturn(scanned);
        when(dismissedRules.load()).thenReturn(Set.of());
        when(scanner.applyDismissals(eq(scanned), any())).thenReturn(scanned);

        MockMvc mvc =
                standaloneSetup(new MemoryController(scanner, dismissedRules)).build();

        mvc.perform(post("/bootui/api/memory/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.violationsFound").value(3));
        verify(scanner).scan();
    }
}
