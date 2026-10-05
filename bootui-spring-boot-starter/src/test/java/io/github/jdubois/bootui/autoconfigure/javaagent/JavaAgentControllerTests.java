package io.github.jdubois.bootui.autoconfigure.javaagent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class JavaAgentControllerTests {

    @Test
    void servesTheReportWithItsSetupSnippetsWithoutTheAgent() throws Exception {
        JavaAgentService service = new JavaAgentService(
                AgentBridgeAccess.absent(),
                () -> null,
                new JavaAgentSettings("1.19.0", "spring", true, null, Path.of("/m2"), Path.of("/nowhere"), false));
        MockMvc mvc = standaloneSetup(new JavaAgentController(service)).build();

        mvc.perform(get("/bootui/api/java-agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_ATTACHED"))
                .andExpect(jsonPath("$.expectedProtocol").value(1))
                .andExpect(jsonPath("$.protocol").doesNotExist())
                .andExpect(jsonPath("$.sensors.length()").value(0))
                .andExpect(jsonPath("$.setup.jarFound").value(false))
                .andExpect(jsonPath("$.setup.buildTool").value("UNKNOWN"))
                .andExpect(jsonPath("$.setup.snippets[0].id").value("maven-download"))
                .andExpect(jsonPath("$.setup.snippets[1].id").value("maven-plugin"));
    }
}
