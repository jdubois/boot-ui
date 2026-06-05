package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.CopilotActivityEvent;
import io.github.jdubois.bootui.core.dto.CopilotSessionDetail;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static CopilotSessionStore storeFor(BootUiProperties properties, Clock clock) {
        CopilotSessionStore store = new CopilotSessionStore(properties.getCopilot(), clock);
        store.refresh();
        return store;
    }

    @Test
    void streamRejectsBeyondConcurrentLimit(@TempDir Path tempDir) {
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        CopilotController controller = new CopilotController(store, props);

        for (int i = 0; i < CopilotController.MAX_CONCURRENT_STREAMS; i++) {
            controller.stream();
        }
        assertThat(controller.emittersForTesting()).hasSize(CopilotController.MAX_CONCURRENT_STREAMS);

        // The next stream is over the cap: it is completed with an error and never tracked.
        controller.stream();
        assertThat(controller.emittersForTesting()).hasSize(CopilotController.MAX_CONCURRENT_STREAMS);
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
    void sessionsAndDashboardUseHomeRelativeSessionDirectory(@TempDir Path tempDir) throws Exception {
        String previousHome = System.getProperty("user.home");
        Path sessionStateDir = tempDir.resolve(".copilot").resolve("session-state");
        Files.createDirectories(sessionStateDir);
        try {
            System.setProperty("user.home", tempDir.toString());
            BootUiProperties props = propertiesFor(sessionStateDir);
            CopilotSessionStore store = storeFor(props);
            MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

            mvc.perform(get("/bootui/api/copilot/sessions").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionStateDir").value("~/.copilot/session-state"));
            mvc.perform(get("/bootui/api/copilot/dashboard").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionStateDir").value("~/.copilot/session-state"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
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
                .andExpect(jsonPath("$.returned").value(1))
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
                .andExpect(jsonPath("$.returned").value(1))
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
    void sessionsListIsLimitedByConfiguredMaximum(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("old.json"), "{\"updated_at\":1700000000,\"events\":[{\"type\":\"t\"}]}");
        Files.writeString(tempDir.resolve("middle.json"), "{\"updated_at\":1700000001,\"events\":[{\"type\":\"t\"}]}");
        Files.writeString(tempDir.resolve("new.json"), "{\"updated_at\":1700000002,\"events\":[{\"type\":\"t\"}]}");
        BootUiProperties props = propertiesFor(tempDir);
        props.getCopilot().setMaxSessions(2);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.returned").value(2))
                .andExpect(jsonPath("$.maxSessions").value(2))
                .andExpect(jsonPath("$.sessions[0].id").value("new"))
                .andExpect(jsonPath("$.sessions[1].id").value("middle"))
                .andExpect(jsonPath("$.warnings[0]").value("Showing the 2 most recent Copilot sessions out of 3."));
    }

    @Test
    void refreshParsesOnlyMostRecentlyModifiedSessions(@TempDir Path tempDir) throws Exception {
        Path old = tempDir.resolve("old.json");
        Path middle = tempDir.resolve("middle.json");
        Path latest = tempDir.resolve("new.json");
        Files.writeString(old, "{\"updated_at\":1700000000,\"events\":[{\"type\":\"t\"}]}");
        Files.writeString(middle, "{\"updated_at\":1700000001,\"events\":[{\"type\":\"t\"}]}");
        Files.writeString(latest, "{\"updated_at\":1700000002,\"events\":[{\"type\":\"t\"}]}");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1));
        Files.setLastModifiedTime(middle, FileTime.fromMillis(2));
        Files.setLastModifiedTime(latest, FileTime.fromMillis(3));
        BootUiProperties props = propertiesFor(tempDir);
        props.getCopilot().setMaxSessions(10);
        props.getCopilot().setMaxParsedSessions(2);
        CopilotSessionStore store = storeFor(props);

        CopilotSessionListDto first = store.listSessions();
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.returned()).isEqualTo(2);
        assertThat(first.sessions()).extracting(summary -> summary.id()).containsExactly("new", "middle");
        assertThat(first.warnings())
                .containsExactly(
                        "Loaded the 2 most recently modified Copilot session files out of 3; increase bootui.copilot.max-parsed-sessions to inspect older sessions.");
        assertThat(store.getSession("old")).isNull();

        Files.setLastModifiedTime(old, FileTime.fromMillis(4));
        store.refresh();

        CopilotSessionListDto refreshed = store.listSessions();
        assertThat(refreshed.sessions()).extracting(summary -> summary.id()).containsExactly("new", "old");
        assertThat(store.getSession("old")).isNotNull();
        assertThat(store.getSession("middle")).isNull();
    }

    @Test
    void sessionsListCanFilterByActivityWindow(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("old.json"), "{\"updated_at\":1700000000,\"events\":[{\"type\":\"t\"}]}");
        Files.writeString(tempDir.resolve("new.json"), "{\"updated_at\":1700000100,\"events\":[{\"type\":\"t\"}]}");
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/sessions")
                        .param("since", "1700000050000")
                        .param("until", "1700000200000")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.sessions[0].id").value("new"));
    }

    @Test
    void dashboardAggregatesJsonlSessions(@TempDir Path tempDir) throws Exception {
        Path firstSession = tempDir.resolve("s1");
        Path secondSession = tempDir.resolve("s2");
        Files.createDirectories(firstSession);
        Files.createDirectories(secondSession);
        Files.writeString(firstSession.resolve("events.jsonl"), """
                {"id":"s1-start","type":"session.start","timestamp":"2026-05-28T10:00:00Z","data":{"model":"gpt-5.5","context":{"cwd":"/work/one"}}}
                {"id":"s1-edit","type":"tool.execution_start","timestamp":"2026-05-28T10:15:00Z","data":{"toolName":"apply_patch","success":true}}
                {"id":"s1-message","type":"assistant.message","timestamp":"2026-05-28T10:30:00Z","data":{"turnId":"turn-1","outputTokens":9,"content":"hidden"}}
                {"id":"s1-search","type":"tool.execution_complete","timestamp":"2026-05-28T11:00:00Z","data":{"toolName":"grep","success":false}}
                {"id":"s1-shutdown","type":"session.shutdown","timestamp":"2026-05-28T11:30:00Z","data":{"tokenDetails":{"input":{"tokenCount":334},"cache_read":{"tokenCount":800},"cache_write":{"tokenCount":100},"output":{"tokenCount":15}},"modelMetrics":{"gpt-5.5":{"usage":{"inputTokens":0,"outputTokens":0,"cacheReadTokens":900}}}}}
                """);
        Files.writeString(secondSession.resolve("events.jsonl"), """
                {"id":"s2-start","type":"session.start","timestamp":"2026-05-27T12:30:00Z","data":{"model":"gpt-4o","context":{"cwd":"/work/two"}}}
                {"id":"s2-mcp","type":"tool.execution_start","timestamp":"2026-05-27T13:00:00Z","data":{"toolName":"mcp.fetch","success":true}}
                {"id":"s2-message","type":"assistant.message","timestamp":"2026-05-27T13:15:00Z","data":{"turnId":"turn-1","outputTokens":7,"content":"hidden"}}
                """);
        BootUiProperties props = propertiesFor(tempDir);
        Clock clock = Clock.fixed(Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC);
        CopilotSessionStore store = storeFor(props, clock);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.sessionCount").value(2))
                .andExpect(jsonPath("$.eventCount").value(6))
                .andExpect(jsonPath("$.totalInputTokens").value(1234))
                .andExpect(jsonPath("$.totalOutputTokens").value(22))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.activeLast24Hours").value(2))
                .andExpect(jsonPath("$.activeLast7Days").value(2))
                .andExpect(jsonPath("$.sessionsWithSchemaDrift").value(0))
                .andExpect(jsonPath("$.categoryCounts[0].label").value("OTHER"))
                .andExpect(jsonPath("$.categoryCounts[0].count").value(3))
                .andExpect(jsonPath("$.topTools[0].label").value("apply_patch"))
                .andExpect(jsonPath("$.modelCounts[0].count").value(1))
                .andExpect(jsonPath("$.activityBuckets.length()").value(24))
                .andExpect(jsonPath("$.activityBuckets[0].outputTokens").value(7))
                .andExpect(jsonPath("$.activityBuckets[22].inputTokens").value(1234))
                .andExpect(jsonPath("$.activityBuckets[22].outputTokens").value(15))
                .andExpect(jsonPath("$.dailyActivityBuckets.length()").value(7))
                .andExpect(jsonPath("$.dailyActivityBuckets[5].eventCount").value(2))
                .andExpect(jsonPath("$.dailyActivityBuckets[5].outputTokens").value(7))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].eventCount").value(4))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].errorCount").value(1))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].inputTokens").value(1234))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].outputTokens").value(15))
                .andExpect(jsonPath("$.recentSessions.length()").value(2))
                .andExpect(jsonPath("$.recentSessions[0].inputTokens").value(1234))
                .andExpect(jsonPath("$.recentSessions[0].outputTokens").value(15))
                .andExpect(jsonPath("$.recentSessions[1].outputTokens").value(7));

        mvc.perform(get("/bootui/api/copilot/sessions/s1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.inputTokens").value(1234))
                .andExpect(jsonPath("$.summary.outputTokens").value(15))
                .andExpect(jsonPath("$.turns[1].outputTokens").value(9));
    }

    @Test
    void dashboardReturnsUnavailableWhenDirectoryMissing(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("missing");
        BootUiProperties props = propertiesFor(missing);
        CopilotSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new CopilotController(store, props)).build();

        mvc.perform(get("/bootui/api/copilot/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.sessionCount").value(0))
                .andExpect(jsonPath("$.activityBuckets.length()").value(0));
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
    void failureEventsIncludeInheritedToolNames(@TempDir Path tempDir) throws Exception {
        Path sessionDir = tempDir.resolve("s1");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("events.jsonl"), """
                {"id":"start","type":"tool.execution_start","timestamp":1,"data":{"toolName":"create"}}
                {"id":"failed","parentId":"start","type":"tool.execution_complete","timestamp":2,"data":{"success":false}}
                """);
        BootUiProperties props = propertiesFor(tempDir);
        CopilotSessionStore store = storeFor(props);

        CopilotSessionDetail detail = store.getSession("s1");

        assertThat(detail.failureEvents()).hasSize(1);
        assertThat(detail.failureEvents().get(0).toolName()).isEqualTo("create");
        assertThat(detail.failureEvents().get(0).summary()).contains("create", "failed");
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
