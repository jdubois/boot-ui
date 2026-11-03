package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.jdubois.bootui.engine.cli.CliCommandPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Structural payload guards, not a substitute for scenario-based agent evaluation. */
class AgentPlaybookTests {

    private static final List<String> PLAYBOOKS = List.of(
            "application-checkup",
            "database-efficiency",
            "slow-endpoint",
            "errors-and-hidden-failures",
            "verify-my-changes",
            "jvm-performance-and-memory");

    private static final Pattern LINK = Pattern.compile("\\[[^\\]\\n]+]\\(([^\\s)]+)\\)");

    private static final Pattern TOOL_ROW =
            Pattern.compile("^\\| `([a-z][a-z0-9_]+)` \\| `bootui ([^`]+)`", Pattern.MULTILINE);

    private static final Pattern CLI_OPTION = Pattern.compile("--([a-z][a-z-]+)");

    @Test
    void bothPayloadsDiscoverEveryStructuredPlaybook() throws IOException {
        for (String root : List.of("skills/bootui", "plugins/bootui/skills/bootui")) {
            Path skill = RepositoryFiles.file(root);
            String selector = Files.readString(skill.resolve("SKILL.md"));
            String index = Files.readString(skill.resolve("references/playbooks/README.md"));
            for (String playbook : PLAYBOOKS) {
                assertThat(selector).as(root).contains("(references/playbooks/" + playbook + ".md)");
                assertThat(index).as(root).contains("(" + playbook + ".md)");
                String contents = Files.readString(skill.resolve("references/playbooks/" + playbook + ".md"));
                assertThat(contents)
                        .as("%s/%s", root, playbook)
                        .contains(
                                "## Goal and entry",
                                "## Prerequisites",
                                "## Investigation",
                                "## Fix and verify",
                                "## Stop and report",
                                "(README.md)");
            }
        }
    }

    @Test
    void relativeSkillLinksStayInsideEachInstalledPayloadAndResolve() throws IOException {
        for (String root : List.of("skills/bootui", "plugins/bootui/skills/bootui")) {
            Path skill = RepositoryFiles.file(root).toAbsolutePath().normalize();
            try (var files = Files.walk(skill)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".md")).toList()) {
                    Matcher links = LINK.matcher(Files.readString(file));
                    while (links.find()) {
                        String link = links.group(1);
                        if (link.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                            continue;
                        }
                        String[] parts = link.split("#", 2);
                        Path target = parts[0].isEmpty()
                                ? file
                                : file.getParent().resolve(parts[0]).normalize();
                        assertThat(target)
                                .as("%s links to %s outside the installed skill", file, link)
                                .startsWith(skill);
                        assertThat(target).as("%s links to %s", file, link).isRegularFile();
                        if (parts.length == 2 && !parts[1].isEmpty()) {
                            assertThat(Files.readAllLines(target).stream()
                                            .filter(line -> line.startsWith("#"))
                                            .map(AgentPlaybookTests::headingId)
                                            .toList())
                                    .as("%s links to missing heading %s", file, link)
                                    .contains(parts[1]);
                        }
                    }
                }
            }
        }
    }

    @Test
    void documentedToolTablesUseRealCatalogToolsAndCliPaths() throws IOException {
        Path root = RepositoryFiles.file("skills/bootui/references/playbooks");
        for (String playbook : PLAYBOOKS) {
            Matcher tools = TOOL_ROW.matcher(Files.readString(root.resolve(playbook + ".md")));
            int count = 0;
            while (tools.find()) {
                String tool = tools.group(1);
                String command = tools.group(2);
                assertThat(McpToolCatalog.names())
                        .as("%s names %s", playbook, tool)
                        .contains(tool);
                String path = CliCommandPaths.BY_TOOL.get(tool);
                assertThat(path).as(tool).isNotBlank();
                assertThat(command.equals(path) || command.startsWith(path + " "))
                        .as("%s must map %s to bootui %s, not %s", playbook, tool, path, command)
                        .isTrue();
                assertCommandOptions(tool, command);
                count++;
            }
            assertThat(count)
                    .as("%s must provide concrete MCP/CLI entry points", playbook)
                    .isPositive();
        }
    }

    @Test
    void optionGuardRejectsFlagsNotSupportedByTheToolSchema() {
        assertCommandOptions("get_http_routes", "http routes --limit 20 --json");
        assertCommandOptions("get_agent_status", "agent status --query files --json");
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertCommandOptions("get_http_routes", "http routes --query orders --json"));
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertCommandOptions("get_agent_status", "agent status --limit 20 --json"));
    }

    private static void assertCommandOptions(String tool, String command) {
        Matcher options = CLI_OPTION.matcher(command);
        while (options.find()) {
            String option = options.group(1);
            if (option.equals("json")) {
                continue;
            }
            String argument = option.equals("scan-id") ? "scanId" : option;
            assertThat(McpToolCatalog.byName(tool).orElseThrow().schema().argumentNames())
                    .as("bootui %s uses --%s, which %s does not advertise", command, option, tool)
                    .contains(argument);
        }
    }

    private static String headingId(String heading) {
        return heading.replaceFirst("^#+\\s+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s_-]", "")
                .replaceAll("\\s", "-");
    }
}
