package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.engine.mcp.McpToolGuide;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the projection actually holds at runtime.
 *
 * <p>The manifest tests check the data; this one runs every single command and asserts it reaches the tool it
 * claims to. That is what catches a command tree that builds fine but shadows a leaf, mis-nests a group, or
 * declares an argument the tool does not take.
 */
class CommandTreeTests {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void everyCommandInTheManifestInvokesTheToolItProjects() {
        Map<String, String> failures = new LinkedHashMap<>();

        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            paths.clear();
            List<String> args = new ArrayList<>(tool.path());
            if (tool.takesId()) {
                args.add("some-id");
            }
            if (tool.takesScanId()) {
                args.addAll(List.of("--scan-id", "scan-1"));
            }
            int exitCode = run(args);
            String expected = "/bootui/api/cli/tools/" + tool.name();
            if (exitCode != ExitCodes.SUCCESS || paths.size() != 1 || !expected.equals(paths.get(0))) {
                failures.put(tool.command(), "exit " + exitCode + ", called " + paths);
            }
        }

        assertThat(failures).as("commands that did not reach their tool").isEmpty();
    }

    @Test
    void everyCommandAcceptsExactlyTheArgumentsItsSchemaDeclares() {
        Map<String, String> failures = new LinkedHashMap<>();

        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            check(failures, tool, "--query", tool.takesQuery());
            check(failures, tool, "--limit", tool.takesLimit() || tool.ignoresLimit());
            check(failures, tool, "--scan-id", tool.takesScanId());
            check(failures, tool, "--offset", tool.takesOffset());
        }

        assertThat(failures)
                .as("commands whose flags disagree with their MCP schema")
                .isEmpty();
    }

    @Test
    void anIdCommandRefusesToRunWithoutOne() {
        ToolManifest.Tool tool = ToolManifest.bundled().byName("get_exception_detail");

        int exitCode = run(tool.path());

        assertThat(exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(paths).as("a missing id must not become a call").isEmpty();
    }

    @Test
    void comparisonDefaultsToPreviousAndStillAcceptsAnExplicitRunId() {
        assertThat(run(List.of("insights", "compare"))).isEqualTo(ExitCodes.SUCCESS);
        assertThat(bodies).containsExactly("{}");
        bodies.clear();
        assertThat(run(List.of("insights", "compare", "run-4"))).isEqualTo(ExitCodes.SUCCESS);
        assertThat(bodies).containsExactly("{\"id\":\"run-4\"}");
    }

    @Test
    void aQueryCommandSendsTheFilterItWasGiven() {
        bodies.clear();

        List<String> args =
                new ArrayList<>(ToolManifest.bundled().byName("get_beans").path());
        args.add("--query");
        args.add("dataSource");
        run(args);

        assertThat(bodies).containsExactly("{\"query\":\"dataSource\"}");
    }

    @Test
    void agentStatusAcceptsAnOlderScriptsLimitWithoutSendingIt() {
        bodies.clear();

        List<String> args = new ArrayList<>(
                ToolManifest.bundled().byName("get_agent_status").path());
        args.addAll(List.of("--query", "executors", "--limit", "3"));

        assertThat(run(args)).isEqualTo(ExitCodes.SUCCESS);
        assertThat(bodies).containsExactly("{\"query\":\"executors\"}");
    }

    @Test
    void agentSensorCommandsSendTheSensorIdAndRequireApproval() {
        for (String toolName : List.of("enable_agent_sensor", "disable_agent_sensor")) {
            ToolManifest.Tool tool = ToolManifest.bundled().byName(toolName);
            List<String> args = new ArrayList<>(tool.path());
            args.add("security-sinks");

            assertThat(run(args)).isEqualTo(ExitCodes.SUCCESS);
            assertThat(bodies).containsExactly("{\"id\":\"security-sinks\"}");
            bodies.clear();

            String help = help(tool.path().toArray(String[]::new));
            assertThat(help)
                    .contains(
                            ToolManifest.Tool.ACTION_TAG,
                            "user's authorization",
                            "Request-value",
                            "retains query/path values");
        }
        assertThat(paths)
                .containsExactly(
                        "/bootui/api/cli/tools/enable_agent_sensor", "/bootui/api/cli/tools/disable_agent_sensor");
    }

    @Test
    void advisorCommandsRequireTheScanIdAndForwardEveryPageArgument() {
        for (String group : List.of("architecture", "hibernate", "spring", "rest-api", "memory", "security", "db")) {
            paths.clear();
            assertThat(run(List.of(group, "violations", "RULE-1"))).isEqualTo(ExitCodes.ERROR);
            assertThat(paths).isEmpty();
            bodies.clear();
            assertThat(run(List.of(
                            group, "violations", "RULE-1", "--scan-id", "scan-1", "--offset", "22", "--limit", "7")))
                    .isEqualTo(ExitCodes.SUCCESS);
            assertThat(bodies).containsExactly("{\"limit\":7,\"id\":\"RULE-1\",\"scanId\":\"scan-1\",\"offset\":22}");
        }
    }

    @Test
    void advisorHelpDocumentsRequiredSnapshotAndOffset() {
        StringWriter output = new StringWriter();
        int status = BootUiCli.run(
                new String[] {"architecture", "violations", "--help"},
                Map.of(),
                true,
                new PrintWriter(output, true),
                new PrintWriter(output, true));
        assertThat(status).isEqualTo(ExitCodes.SUCCESS);
        assertThat(output.toString()).contains("--scan-id", "--offset", "--limit", "<id>");
        assertThat(paths).isEmpty();
    }

    @Test
    void anActionsHelpPrintsItsWholeDescriptionWithTheApprovalTag() {
        String probe = help("probe", "start");
        assertThat(probe)
                .contains(ToolManifest.Tool.ACTION_TAG)
                .contains("separate approval")
                .contains("Metadata only")
                .contains("never argument or return values");
        assertThat(help("memory", "scan")).contains(ToolManifest.Tool.ACTION_TAG, "full GC");
        assertThat(help("sql", "clear")).contains(ToolManifest.Tool.ACTION_TAG);
        assertThat(help("memory", "report")).doesNotContain(ToolManifest.Tool.ACTION_TAG);
        assertThat(paths).isEmpty();
    }

    @Test
    void theCommandListingTagsEveryActionAndNoRead() {
        StringWriter output = new StringWriter();
        BootUiCli.run(
                new String[] {"--help"}, Map.of(), false, new PrintWriter(output, true), new PrintWriter(output, true));
        String listing = output.toString();
        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            String synopsis = tool.synopsis();
            int at = listing.indexOf("  " + synopsis + System.lineSeparator());
            assertThat(at).as(synopsis).isNotNegative();
            String detail = listing.substring(at + synopsis.length() + 2).stripLeading();
            assertThat(detail.startsWith(ToolManifest.Tool.ACTION_TAG))
                    .as("%s tagged", tool.command())
                    .isEqualTo(tool.action());
        }
    }

    private String help(String... command) {
        StringWriter output = new StringWriter();
        List<String> args = new ArrayList<>(List.of(command));
        args.add("--help");
        int status = BootUiCli.run(
                args.toArray(String[]::new),
                Map.of(),
                false,
                new PrintWriter(output, true),
                new PrintWriter(output, true));
        assertThat(status).isEqualTo(ExitCodes.SUCCESS);
        // picocli wraps descriptions to the terminal width, so a phrase may span two lines.
        return output.toString().replaceAll("\\s+", " ");
    }

    @Test
    void everyExampleInTheHelpRunsItsToolWithExactlyTheArgumentsItShows() {
        Map<String, String> failures = new LinkedHashMap<>();

        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            paths.clear();
            bodies.clear();
            List<String> words = shellWords(tool.example());
            if (!words.get(0).equals("bootui")) {
                failures.put(tool.command(), "does not start with bootui: " + tool.example());
                continue;
            }
            int exitCode = run(words.subList(1, words.size()));
            if (exitCode != ExitCodes.SUCCESS || !paths.equals(List.of("/bootui/api/cli/tools/" + tool.name()))) {
                failures.put(tool.command(), "exit " + exitCode + ", called " + paths);
                continue;
            }
            JsonValue body = JsonValue.parse(bodies.get(0));
            Map<String, Object> expected = McpToolGuide.example(tool.name());
            Map<String, String> sent = new LinkedHashMap<>();
            for (String name : body.names()) {
                sent.put(name, body.get(name).asDisplayText());
            }
            Map<String, String> wanted = new LinkedHashMap<>();
            expected.forEach((name, value) -> wanted.put(name, String.valueOf(value)));
            if (!sent.equals(wanted)) {
                failures.put(tool.command(), "sent " + sent + " for " + wanted);
            }
        }

        assertThat(failures).as("examples that do not do what they show").isEmpty();
    }

    @Test
    void aMissingIdPrintsWhereTheIdComesFrom() {
        StringWriter output = new StringWriter();
        int status = BootUiCli.run(
                new String[] {"insights", "show"},
                Map.of(),
                false,
                new PrintWriter(output, true),
                new PrintWriter(output, true));

        assertThat(status).isEqualTo(ExitCodes.ERROR);
        assertThat(output.toString())
                .contains("Missing required parameter", "An observation id from 'bootui insights list'.");
        assertThat(paths).isEmpty();
    }

    /** {@code line} split the way a POSIX shell splits it, for the single-quoting the examples use. */
    private static List<String> shellWords(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        boolean inWord = false;
        for (char c : line.toCharArray()) {
            if (escaped) {
                word.append(c);
                escaped = false;
            } else if (c == '\'') {
                quoted = !quoted;
                inWord = true;
            } else if (c == '\\' && !quoted) {
                escaped = true;
                inWord = true;
            } else if (c == ' ' && !quoted) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    private void check(Map<String, String> failures, ToolManifest.Tool tool, String flag, boolean supported) {
        paths.clear();
        List<String> args = new ArrayList<>(tool.path());
        if (tool.takesId()) {
            args.add("some-id");
        }
        if (tool.takesScanId() && !flag.equals("--scan-id")) {
            args.addAll(List.of("--scan-id", "scan-1"));
        }
        args.add(flag);
        args.add("--limit".equals(flag) || "--offset".equals(flag) ? "3" : "x");

        int exitCode = run(args);
        boolean accepted = exitCode == ExitCodes.SUCCESS;
        if (accepted != supported) {
            failures.put(
                    tool.command() + " " + flag,
                    supported ? "rejected but the schema declares it" : "accepted but the schema does not");
        }
    }

    private int run(List<String> command) {
        List<String> args = new ArrayList<>();
        args.add("--url");
        args.add("http://127.0.0.1:" + server.getAddress().getPort());
        args.addAll(command);
        StringWriter sink = new StringWriter();
        return BootUiCli.run(
                args.toArray(new String[0]), Map.of(), true, new PrintWriter(sink, true), new PrintWriter(sink, true));
    }
}
