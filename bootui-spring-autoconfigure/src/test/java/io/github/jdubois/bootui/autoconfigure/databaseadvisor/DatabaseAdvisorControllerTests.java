package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

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

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorScanStatusDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link DatabaseAdvisorController}. The scan logic lives in the engine
 * {@link DatabaseAdvisorScanner} (covered by {@code DatabaseAdvisorScannerTests}/
 * {@code DatabaseAdvisorRulesTests} in {@code bootui-engine}), so here we only assert that {@code GET}
 * returns the cached report, that {@code POST /scan} refreshes the cache, and that both routes feed the
 * adapter's dismissed-rule ids through {@link DatabaseAdvisorScanner#applyDismissals} before
 * serialization.
 */
class DatabaseAdvisorControllerTests {

    private static DatabaseAdvisorReport report(String status, int violationsFound) {
        return new DatabaseAdvisorReport(
                true,
                "disclaimer",
                List.of("dataSource"),
                4,
                8,
                violationsFound,
                List.of(),
                new DatabaseAdvisorScanStatusDto(
                        "BootUI Database Advisor", status, "message", null, 8, 4, violationsFound),
                List.of());
    }

    @Test
    void getReturnsCachedInitialReportWithDismissalsApplied() throws Exception {
        DatabaseAdvisorScanner scanner = mock(DatabaseAdvisorScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        DatabaseAdvisorReport initial = report("NOT_SCANNED", 0);
        DatabaseAdvisorReport dismissedView = report("NOT_SCANNED", 0);
        when(scanner.initialReport()).thenReturn(initial);
        when(dismissedRules.load()).thenReturn(Set.of("DB-IGNORED"));
        when(scanner.applyDismissals(eq(initial), eq(Set.of("DB-IGNORED")))).thenReturn(dismissedView);

        MockMvc mvc = standaloneSetup(new DatabaseAdvisorController(scanner, dismissedRules))
                .build();

        mvc.perform(get("/bootui/api/database-advisor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"));
        verify(scanner).applyDismissals(initial, Set.of("DB-IGNORED"));
    }

    @Test
    void scanRefreshesCachedReportAndAppliesDismissals() throws Exception {
        DatabaseAdvisorScanner scanner = mock(DatabaseAdvisorScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        when(scanner.initialReport()).thenReturn(report("NOT_SCANNED", 0));
        DatabaseAdvisorReport scanned = report("SCANNED", 2);
        when(scanner.scan()).thenReturn(scanned);
        when(dismissedRules.load()).thenReturn(Set.of());
        when(scanner.applyDismissals(eq(scanned), any())).thenReturn(scanned);

        MockMvc mvc = standaloneSetup(new DatabaseAdvisorController(scanner, dismissedRules))
                .build();

        mvc.perform(post("/bootui/api/database-advisor/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.violationsFound").value(2));
        verify(scanner).scan();
    }

    @Test
    void getReturnsTheLastScannedReportAfterAScan() throws Exception {
        DatabaseAdvisorScanner scanner = mock(DatabaseAdvisorScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        when(scanner.initialReport()).thenReturn(report("NOT_SCANNED", 0));
        DatabaseAdvisorReport scanned = report("SCANNED", 1);
        when(scanner.scan()).thenReturn(scanned);
        when(dismissedRules.load()).thenReturn(Set.of());
        when(scanner.applyDismissals(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMvc mvc = standaloneSetup(new DatabaseAdvisorController(scanner, dismissedRules))
                .build();

        mvc.perform(post("/bootui/api/database-advisor/scan")).andExpect(status().isOk());
        mvc.perform(get("/bootui/api/database-advisor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"));
    }
}
