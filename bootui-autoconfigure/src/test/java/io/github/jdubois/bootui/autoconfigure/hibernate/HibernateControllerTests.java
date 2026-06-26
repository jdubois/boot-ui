package io.github.jdubois.bootui.autoconfigure.hibernate;

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

import io.github.jdubois.bootui.autoconfigure.web.DismissedRulesStore;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateScanStatusDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link HibernateController}. The scan logic lives in the engine
 * {@link HibernateScanner} (covered by {@code HibernateScannerTests}/{@code HibernateRulesTests} in
 * {@code bootui-engine}), so here we only assert that {@code GET} returns the cached report, that
 * {@code POST /scan} refreshes the cache, and that both routes feed the adapter's dismissed-rule ids
 * through {@link HibernateScanner#applyDismissals} before serialization.
 */
class HibernateControllerTests {

    private static HibernateReport report(String status, int violationsFound) {
        return new HibernateReport(
                true,
                "disclaimer",
                List.of("com.example.app"),
                4,
                12,
                violationsFound,
                List.of(),
                new HibernateScanStatusDto("BootUI Hibernate Advisor", status, "message", null, 12, 4, violationsFound),
                List.of());
    }

    @Test
    void getReturnsCachedInitialReportWithDismissalsApplied() throws Exception {
        HibernateScanner scanner = mock(HibernateScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        HibernateReport initial = report("NOT_SCANNED", 0);
        HibernateReport dismissedView = report("NOT_SCANNED", 0);
        when(scanner.initialReport()).thenReturn(initial);
        when(dismissedRules.load()).thenReturn(Set.of("HIB-IGNORED"));
        when(scanner.applyDismissals(eq(initial), eq(Set.of("HIB-IGNORED")))).thenReturn(dismissedView);

        MockMvc mvc = standaloneSetup(new HibernateController(scanner, dismissedRules))
                .build();

        mvc.perform(get("/bootui/api/hibernate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"));
        verify(scanner).applyDismissals(initial, Set.of("HIB-IGNORED"));
    }

    @Test
    void scanRefreshesCachedReportAndAppliesDismissals() throws Exception {
        HibernateScanner scanner = mock(HibernateScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        when(scanner.initialReport()).thenReturn(report("NOT_SCANNED", 0));
        HibernateReport scanned = report("SCANNED", 2);
        when(scanner.scan()).thenReturn(scanned);
        when(dismissedRules.load()).thenReturn(Set.of());
        when(scanner.applyDismissals(eq(scanned), any())).thenReturn(scanned);

        MockMvc mvc = standaloneSetup(new HibernateController(scanner, dismissedRules))
                .build();

        mvc.perform(post("/bootui/api/hibernate/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.violationsFound").value(2));
        verify(scanner).scan();
    }
}
