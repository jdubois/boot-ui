package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitCommandTests {

    @TempDir
    Path tempDir;

    @Test
    void writesVsCodeConfigByDefaultAndMergesIdempotentlyWithoutTouchingOtherServers() throws IOException {
        Path vscodeDir = tempDir.resolve(".vscode");
        Files.createDirectories(vscodeDir);
        Path mcpJson = vscodeDir.resolve("mcp.json");
        Files.writeString(mcpJson, """
                {
                  "inputs": [
                    {
                      "id": "existing-input"
                    }
                  ],
                  "servers": {
                    "github": {
                      "type": "http",
                      "url": "https://api.githubcopilot.com/mcp/"
                    },
                    "bootui": {
                      "type": "http",
                      "url": "http://localhost:8080/old",
                      "headers": {
                        "Authorization": "Bearer old-token"
                      }
                    }
                  }
                }
                """);

        CliResult first = run("init", "--vscode", "--url", "http://localhost:8081", "--api-path", "/ops/api");
        assertThat(first.exitCode).isZero();
        assertThat(first.out).contains(".vscode/mcp.json").contains("http://localhost:8081/ops/api/mcp");

        String afterFirst = Files.readString(mcpJson);
        assertThat(afterFirst)
                .doesNotContain("old-token")
                .doesNotContain("Authorization")
                .doesNotContain("headers");

        JsonValue parsed = JsonValue.parseStrict(afterFirst);
        assertThat(parsed.get("inputs").size()).isEqualTo(1);
        assertThat(parsed.get("servers").get("github").get("url").asString(""))
                .isEqualTo("https://api.githubcopilot.com/mcp/");
        assertThat(parsed.get("servers").get("bootui").get("type").asString("")).isEqualTo("http");
        assertThat(parsed.get("servers").get("bootui").get("url").asString(""))
                .isEqualTo("http://localhost:8081/ops/api/mcp");
        assertThat(parsed.get("servers").get("bootui").get("headers").isMissing())
                .isTrue();

        // Running a second time is byte-for-byte idempotent
        CliResult second = run("init", "--vscode", "--url", "http://localhost:8081", "--api-path", "/ops/api");
        assertThat(second.exitCode).isZero();
        assertThat(Files.readString(mcpJson)).isEqualTo(afterFirst);
    }

    @Test
    void neverWritesTokensOrHeadersEvenWhenTokenFlagOrEnvVarIsPresent() throws IOException {
        CliResult result = runWithEnv(
                Map.of(
                        InitCommand.WORKSPACE_DIR_ENV,
                        tempDir.toString(),
                        "HOME",
                        tempDir.toString(),
                        "BOOTUI_TOKEN",
                        "env-secret-token"),
                "init",
                "--vscode",
                "--token",
                "flag-secret-token");

        assertThat(result.exitCode).isZero();
        Path mcpJson = tempDir.resolve(".vscode").resolve("mcp.json");
        assertThat(mcpJson).exists();
        String written = Files.readString(mcpJson);
        assertThat(written)
                .doesNotContain("env-secret-token")
                .doesNotContain("flag-secret-token")
                .doesNotContain("Authorization")
                .doesNotContain("headers");
    }

    @Test
    void writesCursorConfigWithoutTypeFieldAndPreservesOtherMcpServers() throws IOException {
        Path cursorDir = tempDir.resolve(".cursor");
        Files.createDirectories(cursorDir);
        Path cursorMcp = cursorDir.resolve("mcp.json");
        Files.writeString(cursorMcp, """
                {
                  "mcpServers": {
                    "postgres": {
                      "url": "http://localhost:3000/mcp"
                    }
                  }
                }
                """);

        CliResult result = run("init", "--cursor", "--url", ":9090");
        assertThat(result.exitCode).isZero();

        JsonValue parsed = JsonValue.parseStrict(Files.readString(cursorMcp));
        assertThat(parsed.get("mcpServers").get("postgres").get("url").asString(""))
                .isEqualTo("http://localhost:3000/mcp");
        JsonValue bootui = parsed.get("mcpServers").get("bootui");
        assertThat(bootui.get("url").asString("")).isEqualTo("http://localhost:9090/bootui/api/mcp");
        assertThat(bootui.get("type").isMissing()).isTrue();
    }

    @Test
    void printAndDryRunPreviewWithoutCreatingFiles() {
        CliResult printResult = run("init", "--vscode", "--print");
        assertThat(printResult.exitCode).isZero();
        assertThat(JsonValue.parseStrict(printResult.out)
                        .get("servers")
                        .get("bootui")
                        .get("url")
                        .asString(""))
                .isEqualTo("http://localhost:8080/bootui/api/mcp");
        assertThat(tempDir.resolve(".vscode")).doesNotExist();

        CliResult dryRunResult = run("init", "--cursor", "--dry-run", "--url", "http://127.0.0.1:8082");
        assertThat(dryRunResult.exitCode).isZero();
        assertThat(JsonValue.parseStrict(dryRunResult.out)
                        .get("mcpServers")
                        .get("bootui")
                        .get("url")
                        .asString(""))
                .isEqualTo("http://127.0.0.1:8082/bootui/api/mcp");
        assertThat(tempDir.resolve(".cursor")).doesNotExist();
    }

    @Test
    void claudeTargetPrintsCommandWithoutWritingFiles() {
        CliResult claudeResult = run("init", "--claude", "--url", "http://127.0.0.1:8085");
        assertThat(claudeResult.exitCode).isZero();
        assertThat(claudeResult.out.trim())
                .isEqualTo("claude mcp add --transport http bootui http://127.0.0.1:8085/bootui/api/mcp");
        assertThat(tempDir.resolve(".vscode")).doesNotExist();
        assertThat(tempDir.resolve(".cursor")).doesNotExist();
    }

    @Test
    void rejectsMultipleTargetFlagsOrMalformedExistingJson() throws IOException {
        CliResult multiple = run("init", "--vscode", "--cursor");
        assertThat(multiple.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(multiple.err).contains("Choose one of --vscode, --cursor, or --claude.");

        Path vscodeDir = tempDir.resolve(".vscode");
        Files.createDirectories(vscodeDir);
        Files.writeString(vscodeDir.resolve("mcp.json"), "{not-valid-json");

        CliResult malformed = run("init", "--vscode");
        assertThat(malformed.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(malformed.err).contains("not valid JSON");
    }

    private CliResult run(String... args) {
        return runWithEnv(Map.of(InitCommand.WORKSPACE_DIR_ENV, tempDir.toString(), "HOME", tempDir.toString()), args);
    }

    private static CliResult runWithEnv(Map<String, String> env, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode =
                BootUiCli.run(args, env, true, new PrintWriter(out, true), new PrintWriter(err, true), options -> {
                    throw new AssertionError("Offline init must not open an HTTP client");
                });
        return new CliResult(exitCode, out.toString(), err.toString());
    }

    private record CliResult(int exitCode, String out, String err) {}
}
