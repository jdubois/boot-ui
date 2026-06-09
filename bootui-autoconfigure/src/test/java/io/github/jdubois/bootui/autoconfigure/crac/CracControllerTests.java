package io.github.jdubois.bootui.autoconfigure.crac;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

class CracControllerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.crac.fixtures";

    private MockMvc mvc() {
        CracReadinessScanner scanner =
                new CracReadinessScanner(() -> List.of(FIXTURES), new ClassFileCracImporter(), Clock.systemUTC());
        CracRuntimeStatusCollector collector =
                new CracRuntimeStatusCollector(new MockEnvironment(), List::of, className -> false);
        return standaloneSetup(new CracController(scanner, collector)).build();
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
    void scanRunsReadinessChecksAndReturnsFindings() throws Exception {
        mvc().perform(post("/bootui/api/crac/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("SCANNED"))
                .andExpect(jsonPath("$.checksRun")
                        .value(CracCheckRegistry.activeChecks().size()))
                .andExpect(jsonPath("$.findingsFound").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.runtime").exists());
    }
}
