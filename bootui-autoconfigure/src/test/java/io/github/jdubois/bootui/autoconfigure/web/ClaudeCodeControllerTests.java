package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.CopilotActivityEvent;
import io.github.jdubois.bootui.core.dto.CopilotSessionDetail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ClaudeCodeControllerTests {

    private static BootUiProperties propertiesFor(Path dir) {
        BootUiProperties props = new BootUiProperties();
        props.getClaudeCode().setSessionStateDir(dir.toString());
        return props;
    }

    private static ClaudeCodeSessionStore storeFor(BootUiProperties properties) {
        ClaudeCodeSessionStore store = new ClaudeCodeSessionStore(properties.getClaudeCode());
        store.refresh();
        return store;
    }

    private static ClaudeCodeSessionStore storeFor(BootUiProperties properties, Clock clock) {
        ClaudeCodeSessionStore store = new ClaudeCodeSessionStore(properties.getClaudeCode(), clock);
        store.refresh();
        return store;
    }

    @Test
    void sessionsReturnsUnavailableWhenDirectoryMissing(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("does-not-exist");
        BootUiProperties props = propertiesFor(missing);
        ClaudeCodeSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        mvc.perform(get("/bootui/api/claude-code/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(
                        jsonPath("$.unavailableReason").value("Claude Code session directory not found at " + missing))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void sessionsListsProjectJsonlWithSanitizedToolEvents(@TempDir Path tempDir) throws Exception {
        writeClaudeSession(tempDir.resolve("project-one").resolve("session-one.jsonl"));
        BootUiProperties props = propertiesFor(tempDir);
        ClaudeCodeSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        mvc.perform(get("/bootui/api/claude-code/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.sessions[0].id").value("session-one"))
                .andExpect(jsonPath("$.sessions[0].filename").value("project-one/session-one.jsonl"))
                .andExpect(jsonPath("$.sessions[0].model").value("claude-sonnet-4-20250514"))
                .andExpect(jsonPath("$.sessions[0].workingDirectory").value("/work/app"))
                .andExpect(jsonPath("$.sessions[0].eventCount").value(2))
                .andExpect(jsonPath("$.sessions[0].inputTokens").value(135))
                .andExpect(jsonPath("$.sessions[0].outputTokens").value(20))
                .andExpect(jsonPath("$.sessions[0].errorCount").value(1));

        CopilotSessionDetail detail = store.getSession("session-one");
        assertThat(detail.summary().inputTokens()).isEqualTo(135);
        assertThat(detail.summary().outputTokens()).isEqualTo(20);
        assertThat(detail.turns()).anySatisfy(turn -> {
            assertThat(turn.inputTokens()).isEqualTo(135);
            assertThat(turn.outputTokens()).isEqualTo(20);
        });
        assertThat(detail.recentEvents())
                .extracting(CopilotActivityEvent::toolName)
                .containsExactly("Bash", "Bash");
        assertThat(detail.recentEvents())
                .extracting(CopilotActivityEvent::type)
                .containsExactly("tool_use", "tool_result");
        assertThat(detail.recentEvents().get(1).success()).isFalse();
        assertThat(detail.toString())
                .doesNotContain("SECRET_PROMPT")
                .doesNotContain("SECRET_TEXT")
                .doesNotContain("SECRET_COMMAND")
                .doesNotContain("SECRET_FILE")
                .doesNotContain("SECRET_OUTPUT");
    }

    @Test
    void refreshParsesOnlyMostRecentlyModifiedProjectSessions(@TempDir Path tempDir) throws Exception {
        Path old = tempDir.resolve("project-one").resolve("session-old.jsonl");
        Path middle = tempDir.resolve("project-one").resolve("session-middle.jsonl");
        Path latest = tempDir.resolve("project-two").resolve("session-new.jsonl");
        writeClaudeSession(old);
        writeClaudeSession(middle);
        writeClaudeSession(latest);
        Files.setLastModifiedTime(old, FileTime.fromMillis(1));
        Files.setLastModifiedTime(middle, FileTime.fromMillis(2));
        Files.setLastModifiedTime(latest, FileTime.fromMillis(3));
        BootUiProperties props = propertiesFor(tempDir);
        props.getClaudeCode().setMaxSessions(10);
        props.getClaudeCode().setMaxParsedSessions(1);
        ClaudeCodeSessionStore store = storeFor(props);

        var list = store.listSessions();
        assertThat(list.total()).isEqualTo(1);
        assertThat(list.returned()).isEqualTo(1);
        assertThat(list.sessions()).extracting(summary -> summary.id()).containsExactly("session-new");
        assertThat(list.warnings())
                .containsExactly(
                        "Loaded the 1 most recently modified Claude Code session files out of 3; increase bootui.claude-code.max-parsed-sessions to inspect older sessions.");
        assertThat(store.getSession("session-old")).isNull();
        assertThat(store.getSession("session-middle")).isNull();
    }

    @Test
    void dashboardAggregatesIsoTimestampActivity(@TempDir Path tempDir) throws Exception {
        writeClaudeSession(tempDir.resolve("project-one").resolve("session-one.jsonl"));
        BootUiProperties props = propertiesFor(tempDir);
        Clock clock = Clock.fixed(Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC);
        ClaudeCodeSessionStore store = storeFor(props, clock);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        mvc.perform(get("/bootui/api/claude-code/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.sessionCount").value(1))
                .andExpect(jsonPath("$.eventCount").value(2))
                .andExpect(jsonPath("$.totalInputTokens").value(135))
                .andExpect(jsonPath("$.totalOutputTokens").value(20))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.activeLast24Hours").value(1))
                .andExpect(jsonPath("$.activityBuckets[21].eventCount").value(1))
                .andExpect(jsonPath("$.activityBuckets[21].inputTokens").value(135))
                .andExpect(jsonPath("$.activityBuckets[21].outputTokens").value(20))
                .andExpect(jsonPath("$.activityBuckets[22].eventCount").value(1))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].eventCount").value(2))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].inputTokens").value(135))
                .andExpect(jsonPath("$.dailyActivityBuckets[6].outputTokens").value(20));
    }

    @Test
    void rawRevealIsDisabledByDefault(@TempDir Path tempDir) throws Exception {
        writeClaudeSession(tempDir.resolve("project-one").resolve("session-one.jsonl"));
        BootUiProperties props = propertiesFor(tempDir);
        ClaudeCodeSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        mvc.perform(get("/bootui/api/claude-code/sessions/session-one/events/toolu_1/raw")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void rawRevealReturnsJsonOnlyWhenExplicitlyEnabled(@TempDir Path tempDir) throws Exception {
        writeClaudeSession(tempDir.resolve("project-one").resolve("session-one.jsonl"));
        BootUiProperties props = propertiesFor(tempDir);
        props.getClaudeCode().setAllowRawReveal(true);
        ClaudeCodeSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        String response = mvc.perform(get("/bootui/api/claude-code/sessions/session-one/events/toolu_1/raw")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-one"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("SECRET_COMMAND").doesNotContain("SECRET_OUTPUT");
    }

    @Test
    void rawRevealRespectsMetadataOnlyExposure(@TempDir Path tempDir) throws Exception {
        writeClaudeSession(tempDir.resolve("project-one").resolve("session-one.jsonl"));
        BootUiProperties props = propertiesFor(tempDir);
        props.getClaudeCode().setAllowRawReveal(true);
        props.setExposeValues(BootUiProperties.ValueExposure.METADATA_ONLY);
        ClaudeCodeSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        mvc.perform(get("/bootui/api/claude-code/sessions/session-one/events/toolu_1/raw")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventsFilterByCategory(@TempDir Path tempDir) throws Exception {
        writeClaudeSession(tempDir.resolve("project-one").resolve("session-one.jsonl"));
        BootUiProperties props = propertiesFor(tempDir);
        ClaudeCodeSessionStore store = storeFor(props);
        MockMvc mvc = standaloneSetup(new ClaudeCodeController(store, props)).build();

        mvc.perform(get("/bootui/api/claude-code/sessions/session-one/events")
                        .param("category", "SHELL")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.events[0].toolName").value("Bash"));
    }

    @Test
    void summaryAndSystemLinesDoNotProducePhantomEvents(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("project-one").resolve("session-quiet.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"type":"summary","uuid":"s1","summary":"SECRET_COMPACT_SUMMARY","leafUuid":"a1"}
                {"type":"system","uuid":"sys1","subtype":"compact_boundary","timestamp":"2026-05-28T09:00:00Z","compactMetadata":{"trigger":"auto","preTokens":42000}}
                {"type":"system","uuid":"sys2","subtype":"turn_duration","parentUuid":"a1","timestamp":"2026-05-28T09:00:01Z","durationMs":1234}
                {"type":"user","uuid":"u1","timestamp":"2026-05-28T10:00:00Z","cwd":"/work/app","message":{"role":"user","content":"SECRET_PROMPT"}}
                {"type":"assistant","uuid":"a2","parentUuid":"u1","timestamp":"2026-05-28T10:00:01Z","message":{"role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"tool_use","id":"toolu_2","name":"Read","input":{"file_path":"/tmp/SECRET.txt"}}]}}
                """);

        BootUiProperties props = propertiesFor(tempDir);
        ClaudeCodeSessionStore store = storeFor(props);

        CopilotSessionDetail detail = store.getSession("session-quiet");
        assertThat(detail.recentEvents()).extracting(CopilotActivityEvent::type).containsExactly("tool_use");
        assertThat(detail.recentEvents())
                .extracting(CopilotActivityEvent::toolName)
                .containsExactly("Read");
        assertThat(detail.toString())
                .doesNotContain("SECRET_COMPACT_SUMMARY")
                .doesNotContain("SECRET_PROMPT")
                .doesNotContain("compact_boundary")
                .doesNotContain("turn_duration");
    }

    @Test
    void canonicalClaudeToolsAreCategorizedCorrectly(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("project-one").resolve("session-tools.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"type":"assistant","uuid":"a1","timestamp":"2026-05-28T10:00:00Z","message":{"role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"tool_use","id":"t1","name":"WebSearch","input":{"query":"hidden"}}]}}
                {"type":"assistant","uuid":"a2","timestamp":"2026-05-28T10:00:01Z","message":{"role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"tool_use","id":"t2","name":"TodoWrite","input":{"todos":[]}}]}}
                {"type":"assistant","uuid":"a3","timestamp":"2026-05-28T10:00:02Z","message":{"role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"tool_use","id":"t3","name":"EnterPlanMode","input":{}}]}}
                {"type":"assistant","uuid":"a4","timestamp":"2026-05-28T10:00:03Z","message":{"role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"tool_use","id":"t4","name":"ReadMcpResourceTool","input":{}}]}}
                {"type":"assistant","uuid":"a5","timestamp":"2026-05-28T10:00:04Z","message":{"role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"tool_use","id":"t5","name":"mcp__github__list_issues","input":{}}]}}
                """);

        BootUiProperties props = propertiesFor(tempDir);
        ClaudeCodeSessionStore store = storeFor(props);

        CopilotSessionDetail detail = store.getSession("session-tools");
        assertThat(detail.recentEvents())
                .extracting(CopilotActivityEvent::toolName, CopilotActivityEvent::category)
                .containsExactly(
                        tuple("WebSearch", "WEB"),
                        tuple("TodoWrite", "ASK"),
                        tuple("EnterPlanMode", "ASK"),
                        tuple("ReadMcpResourceTool", "SKILL"),
                        tuple("mcp__github__list_issues", "MCP"));
    }

    private static void writeClaudeSession(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"type":"user","uuid":"u1","timestamp":"2026-05-28T10:00:00Z","cwd":"/work/app","message":{"role":"user","content":"SECRET_PROMPT"}}
                {"type":"assistant","uuid":"a1","parentUuid":"u1","timestamp":"2026-05-28T10:15:00Z","cwd":"/work/app","message":{"role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":100,"output_tokens":20,"cache_creation_input_tokens":5,"cache_read_input_tokens":30},"content":[{"type":"text","text":"SECRET_TEXT"},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"echo SECRET_COMMAND","file_path":"/tmp/SECRET_FILE.txt"}}]}}
                {"type":"user","uuid":"u2","parentUuid":"a1","timestamp":"2026-05-28T11:00:00Z","cwd":"/work/app","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"SECRET_OUTPUT","is_error":true}]}}
                """);
    }
}
