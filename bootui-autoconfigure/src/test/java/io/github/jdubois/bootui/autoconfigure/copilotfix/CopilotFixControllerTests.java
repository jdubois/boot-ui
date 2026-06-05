package io.github.jdubois.bootui.autoconfigure.copilotfix;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.GitHubTokenProvider;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class CopilotFixControllerTests {

    private MockMvc mvc(boolean enabled) {
        BootUiProperties properties = new BootUiProperties();
        if (enabled) {
            properties.getCopilotFix().setEnabled(BootUiProperties.Mode.ON);
        }
        GitHubTokenProvider tokenProvider = timeout -> new GitHubTokenProvider.Token("token", "test");
        GitWorkspace workspace = new GitWorkspace() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public Isolated createIsolated(String branch) {
                return new Isolated(Path.of(System.getProperty("java.io.tmpdir")), branch);
            }

            @Override
            public Diff capture(Isolated isolated) {
                return new Diff("diff", 1);
            }

            @Override
            public void cleanup(Isolated isolated, boolean deleteBranch) {}
        };
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider, (context, listener) -> {}, root -> workspace, Path.of("."), Runnable::run);
        return standaloneSetup(new CopilotFixController(service)).build();
    }

    @Test
    void statusReportsAvailabilityWithoutLeakingTheToken() throws Exception {
        mvc(true)
                .perform(get("/bootui/api/copilot-fix/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.tokenPresent").value(true))
                .andExpect(jsonPath("$.sdkPresent").value(false));
    }

    @Test
    void runStartsAndReturnsAccepted() throws Exception {
        mvc(true)
                .perform(post("/bootui/api/copilot-fix/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"descriptor\":{\"findingId\":\"GHSA-1\",\"source\":\"vulnerabilities\",\"title\":\"t\",\"summary\":\"s\",\"severity\":\"HIGH\",\"targets\":[\"a:b:1\"]}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.findingId").value("GHSA-1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void runRejectsMissingFindingId() throws Exception {
        mvc(true)
                .perform(post("/bootui/api/copilot-fix/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descriptor\":{\"findingId\":\"\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runRejectedWhenDisabled() throws Exception {
        mvc(false)
                .perform(post("/bootui/api/copilot-fix/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descriptor\":{\"findingId\":\"GHSA-1\"}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownRunReturnsNotFound() throws Exception {
        mvc(true).perform(get("/bootui/api/copilot-fix/runs/does-not-exist")).andExpect(status().isNotFound());
    }
}
