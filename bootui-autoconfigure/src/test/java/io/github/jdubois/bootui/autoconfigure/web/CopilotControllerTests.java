package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotActivityEvent;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionDetail;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionListDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link CopilotController} backed by a real
 * {@link CopilotSessionStore} pointed at a temporary directory.
 */
class CopilotControllerTests {

    private static BootUiProperties propertiesFor(Path dir) {
        BootUiProperties props = new BootUiProperties();
        props.getCopilot().setSessionStateDir(dir.toString());
        return props;
    }

    private static CopilotSessionStore storeFor(BootUiProperties properties) {
        CopilotSessionStore store = new CopilotSessionStore(properties.getCopilot());
        store.refresh();
        return store;
    }

    @Test
    void sessionsReturnsUnavailableWhenDirectoryMissing(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("does-not-exist");
        BootUiProperties props = propertiesFor(missing);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void sessionsListsParsedSessionsWithCounts(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("abc123.json"), """
                {
                  "model": "gpt-4o",
                  "cwd": "/work",
                  "events": [
                    {"type":"tool_call","tool":"apply_patch","timestamp":1700000000,"success":true,"path":"/work/App.java"},
                    {"type":"tool_call","tool":"mcp.fetch","timestamp":1700000001,"success":false,"url":"https://example.com/x"},
                    {"type":"tool_call","tool":"grep","timestamp":1700000002,"success":true}
                  ]
                }
                """);
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.sessions[0].id").value("abc123"))
                .andExpect(jsonPath("$.sessions[0].model").value("gpt-4o"))
                .andExpect(jsonPath("$.sessions[0].workingDirectory").value("/work"))
                .andExpect(jsonPath("$.sessions[0].eventCount").value(3))
                .andExpect(jsonPath("$.sessions[0].errorCount").value(1));
    }

    @Test
    void sessionsListsJsonlSessionDirectoriesWithSanitizedEvents(@TempDir Path tempDir) throws Exception {
        Path sessionDir = tempDir.resolve("session-1");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("events.jsonl"), """
                {"id":"e1","type":"session.start","timestamp":"2026-05-28T07:00:00Z","data":{"model":"gpt-5.5","context":{"cwd":"/work/project","repository":"jdubois/boot-ui","branch":"main"}}}
                {"id":"e2","type":"user.message","timestamp":"2026-05-28T07:00:01Z","data":{"content":"secret prompt","transformedContent":"secret transformed prompt"}}
                {"id":"e3","type":"tool.execution_start","timestamp":"2026-05-28T07:00:02Z","data":{"toolName":"apply_patch","success":true,"arguments":{"command":"do not expose this"}}}
                {"id":"e4","type":"tool.execution_complete","timestamp":"2026-05-28T07:00:03Z","data":{"toolName":"apply_patch","success":false,"input":{"toolResult":{"textResultForLlm":"secret output"}}}}
                """);
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.sessions[0].id").value("session-1"))
                .andExpect(jsonPath("$.sessions[0].filename").value("session-1/events.jsonl"))
                .andExpect(jsonPath("$.sessions[0].model").value("gpt-5.5"))
                .andExpect(jsonPath("$.sessions[0].workingDirectory").value("/work/project"))
                .andExpect(jsonPath("$.sessions[0].eventCount").value(3))
                .andExpect(jsonPath("$.sessions[0].errorCount").value(1));

        CopilotSessionDetail detail = store.getSession("session-1");
        assertThat(detail.recentEvents()).extracting(CopilotActivityEvent::type).doesNotContain("user.message");
        assertThat(detail.recentEvents().toString())
                .doesNotContain("secret prompt")
                .doesNotContain("secret transformed prompt")
                .doesNotContain("secret output")
                .doesNotContain("do not expose this");
    }

    @Test
    void sessionDetailFlagsSchemaDriftForUnknownShape(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("weird.json"), "{\"hello\":\"world\"}");
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions/weird").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.schemaDrift").value(true))
                .andExpect(jsonPath("$.summary.eventCount").value(0));
    }

    @Test
    void sessionDetailReturns404ForUnknownId(@TempDir Path tempDir) throws Exception {
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions/unknown").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventsFilterByCategoryAndLimit(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("s1.json"), """
                {"events":[
                  {"type":"t","tool":"apply_patch","timestamp":1},
                  {"type":"t","tool":"grep","timestamp":2},
                  {"type":"t","tool":"mcp.list","timestamp":3}
                ]}
                """);
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions/s1/events")
                        .param("category", "MCP")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.events[0].toolName").value("mcp.list"))
                .andExpect(jsonPath("$.events[0].category").value("MCP"));
    }

    @Test
    void rawRevealReturns404WhenDisabled(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("s1.json"), "{\"events\":[{\"type\":\"t\",\"tool\":\"apply_patch\",\"timestamp\":1}]}");
        BootUiProperties props = propertiesFor(tempDir);
        props.getCopilot().setAllowRawReveal(false);
        CopilotSessionStore store = storeFor(props);

        CopilotSessionDetail detail = store.getSession("s1");
        assertThat(detail).isNotNull();
        String eventId = detail.recentEvents().get(0).id();

        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();
        mvc.perform(get("/bootui/api/copilot/sessions/s1/events/" + eventId + "/raw")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void rawRevealReturnsJsonWhenEnabled(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("s1.json"),
                "{\"events\":[{\"type\":\"t\",\"tool\":\"apply_patch\",\"timestamp\":1,\"secret\":\"hello\"}]}");
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        CopilotSessionDetail detail = store.getSession("s1");
        String eventId = detail.recentEvents().get(0).id();

        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();
        mvc.perform(get("/bootui/api/copilot/sessions/s1/events/" + eventId + "/raw")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.json").exists());
    }

    @Test
    void rawRevealReturnsSingleJsonlEventWhenEnabled(@TempDir Path tempDir) throws Exception {
        Path sessionDir = tempDir.resolve("s1");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("events.jsonl"), """
                {"id":"e1","type":"session.start","timestamp":1,"data":{"model":"gpt-5.5"}}
                {"id":"e2","type":"tool.execution_start","timestamp":2,"data":{"toolName":"apply_patch","success":true}}
                """);
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);

        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();
        String response = mvc.perform(
                        get("/bootui/api/copilot/sessions/s1/events/e2/raw").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).contains("apply_patch").doesNotContain("session.start");
    }

    @Test
    void rawRevealRespectsMetadataOnlyExposure(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("s1.json"), "{\"events\":[{\"type\":\"t\",\"tool\":\"apply_patch\",\"timestamp\":1}]}");
        BootUiProperties props = propertiesFor(tempDir);
        props.setExposeValues(BootUiProperties.ValueExposure.METADATA_ONLY);
        CopilotSessionStore store = storeFor(props);
        String eventId = store.getSession("s1").recentEvents().get(0).id();

        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();
        mvc.perform(get("/bootui/api/copilot/sessions/s1/events/" + eventId + "/raw")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void sanitizedSummaryDoesNotLeakSensitiveFields(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("s1.json"), """
                {"events":[
                  {"type":"shell","tool":"bash","timestamp":1,
                   "args":["rm","-rf","/secret"],
                   "output":"super secret output",
                   "prompt":"top-secret prompt",
                   "path":"/work/passwords.txt"}
                ]}
                """);
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        CopilotSessionListDto list = store.listSessions();
        assertThat(list.sessions()).hasSize(1);
        List<CopilotActivityEvent> events = store.getSession("s1").recentEvents();
        assertThat(events).hasSize(1);
        CopilotActivityEvent event = events.get(0);
        assertThat(event.summary()).doesNotContain("super secret output");
        assertThat(event.summary()).doesNotContain("top-secret prompt");
        assertThat(event.summary()).doesNotContain("passwords");
        assertThat(event.summary()).doesNotContain("rm -rf");
    }
}
