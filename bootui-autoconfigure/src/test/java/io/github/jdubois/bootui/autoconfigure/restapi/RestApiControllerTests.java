package io.github.jdubois.bootui.autoconfigure.restapi;

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
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.RestApiScanStatusDto;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link RestApiController}. The scan logic lives in the engine
 * {@link RestApiScanner} (covered by {@code RestApiScannerTests}/{@code RestApiRulesTests} in
 * {@code bootui-engine}), so here we only assert that {@code GET} returns the cached report, that
 * {@code POST /scan} refreshes the cache, and that both routes feed the adapter's dismissed-rule ids
 * through {@link RestApiScanner#applyDismissals} before serialization.
 */
class RestApiControllerTests {

    private static RestApiReport report(String status, int violationsFound) {
        return new RestApiReport(
                true,
                "disclaimer",
                List.of("com.example.app"),
                4,
                12,
                6,
                violationsFound,
                List.of(),
                new RestApiScanStatusDto("BootUI REST API Advisor", status, "message", null, 6, 4, 12, violationsFound),
                List.of());
    }

    @Test
    void getReturnsCachedInitialReportWithDismissalsApplied() throws Exception {
        RestApiScanner scanner = mock(RestApiScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        RestApiReport initial = report("NOT_SCANNED", 0);
        RestApiReport dismissedView = report("NOT_SCANNED", 0);
        when(scanner.initialReport()).thenReturn(initial);
        when(dismissedRules.load()).thenReturn(Set.of("RAPI-IGNORED"));
        when(scanner.applyDismissals(eq(initial), eq(Set.of("RAPI-IGNORED")))).thenReturn(dismissedView);

        MockMvc mvc =
                standaloneSetup(new RestApiController(scanner, dismissedRules)).build();

        mvc.perform(get("/bootui/api/rest-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_SCANNED"));
        verify(scanner).applyDismissals(initial, Set.of("RAPI-IGNORED"));
    }

    @Test
    void scanRefreshesCachedReportAndAppliesDismissals() throws Exception {
        RestApiScanner scanner = mock(RestApiScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        when(scanner.initialReport()).thenReturn(report("NOT_SCANNED", 0));
        RestApiReport scanned = report("SCANNED", 3);
        when(scanner.scan()).thenReturn(scanned);
        when(dismissedRules.load()).thenReturn(Set.of());
        when(scanner.applyDismissals(eq(scanned), any())).thenReturn(scanned);

        MockMvc mvc =
                standaloneSetup(new RestApiController(scanner, dismissedRules)).build();

        mvc.perform(post("/bootui/api/rest-api/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.violationsFound").value(3));
        verify(scanner).scan();
    }
}
