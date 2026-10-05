package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.cli.CliCommandPaths;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps the agent benchmark fixtures of PLAN-v2 §2.2 and §5.17 true to what BootUI offers: every tool, CLI command,
 * prompt, and guidance step a fixture relies on exists, on every stack's guidance text.
 */
class AgentBenchmarkFixturesTests {

    private static final List<String> FRAMEWORKS = List.of("Spring Boot", "Quarkus");

    static Stream<Path> fixtures() throws IOException, URISyntaxException {
        try (Stream<Path> files = Files.list(directory())) {
            return files
                    .filter(file -> !file.getFileName().toString().equals("README.md"))
                    .filter(file -> file.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @Test
    void theBenchmarkAddsTheEleventhInvestigationAndTheSixthRefusalFixture() throws Exception {
        assertThat(fixtures().map(file -> file.getFileName().toString()))
                .containsExactly("investigation-11-did-my-change-run.md", "refusal-6-agent-not-applicable.md");
        String readme = Files.readString(directory().resolve("README.md"));
        fixtures().forEach(file -> assertThat(readme).contains("(" + file.getFileName() + ")"));
        assertThat(readme).contains("m4-20-protocol-2");
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyToolCommandAndPromptAFixtureNamesExists(Path fixture) throws IOException {
        List<String> lines = Files.readAllLines(fixture);
        List<String> tools = new ArrayList<>(values(lines, "tools"));
        tools.addAll(values(lines, "optional-tools"));
        assertThat(tools).isNotEmpty();
        assertThat(McpToolCatalog.names()).containsAll(tools);
        assertThat(CliCommandPaths.BY_TOOL.values()).containsAll(values(lines, "cli"));
        tools.forEach(tool -> assertThat(values(lines, "cli")).contains(CliCommandPaths.BY_TOOL.get(tool)));
        assertThat(values(lines, "prompt"))
                .singleElement()
                .satisfies(prompt -> assertThat(McpGuidance.prompts("Spring Boot"))
                        .extracting(McpPrompt::name)
                        .contains(prompt));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyGuidanceStepAFixtureReliesOnIsInTheGuidance(Path fixture) throws IOException {
        List<String> steps = Files.readAllLines(fixture).stream()
                .filter(line -> line.startsWith("- guidance: "))
                .map(line -> line.substring("- guidance: ".length()))
                .toList();
        assertThat(steps).isNotEmpty();
        for (String step : steps) {
            int colon = step.indexOf(": ");
            String target = step.substring(0, colon);
            String phrase = step.substring(colon + 2);
            for (String framework : FRAMEWORKS) {
                assertThat(guidance(target, framework))
                        .as(target + " on " + framework)
                        .contains(phrase);
            }
        }
    }

    private static String guidance(String target, String framework) {
        if (target.equals("instructions")) {
            return McpGuidance.instructions(framework);
        }
        return McpGuidance.prompts(framework).stream()
                .filter(prompt -> prompt.name().equals(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No prompt " + target))
                .text();
    }

    private static List<String> values(List<String> lines, String key) {
        String prefix = "- " + key + ": ";
        return lines.stream()
                .filter(line -> line.startsWith(prefix))
                .flatMap(line -> Arrays.stream(line.substring(prefix.length()).split(",")))
                .map(String::trim)
                .toList();
    }

    private static Path directory() throws URISyntaxException {
        return Path.of(AgentBenchmarkFixturesTests.class
                .getResource("/agent-benchmark")
                .toURI());
    }
}
