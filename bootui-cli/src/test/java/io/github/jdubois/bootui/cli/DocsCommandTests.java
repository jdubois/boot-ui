package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DocsCommandTests {
    @Test
    void listsEveryTopicAndItsCoverageWithoutAnApplication() {
        Result result = run("docs");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("cli - CLI installation", "properties - Configuration", "java-agent");
        assertThat(result.err()).isEmpty();
        assertThat(JsonValue.parse(run("--json", "docs").out()).get("topics").values())
                .hasSize(DocsCommand.TOPICS.size());
    }

    @Test
    void pagesStayRawMarkdownWhenPipedUnlessJsonIsExplicit() throws Exception {
        String source = DocsCommand.TOPICS.get(0).content();
        assertThat(run("docs", "cli").out()).isEqualTo(source);
        for (String[] args : new String[][] {{"--json", "docs", "cli"}, {"docs", "cli", "--json"}}) {
            JsonValue result = JsonValue.parse(run(args).out());
            assertThat(result.get("content").asString("")).isEqualTo(source);
            assertThat(result.get("file").asString("")).isEqualTo("CLI.md");
            assertThat(result.get("version").asString("")).isEqualTo(BootUiCli.version());
        }
    }

    @Test
    void resourcesAreExactCopiesAndOnlyTheAllowListedCorpusIsPackaged() throws Exception {
        Path root = Path.of(DocsCommand.class.getResource("/bootui-docs").toURI());
        try (var files = Files.walk(root)) {
            Set<String> packaged = files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
            assertThat(packaged)
                    .containsExactlyInAnyOrderElementsOf(DocsCommand.TOPICS.stream()
                            .map(DocsCommand.Topic::file)
                            .toList());
        }
        for (var topic : DocsCommand.TOPICS) {
            assertThat(topic.content()).isEqualTo(Files.readString(Path.of("../docs", topic.file())));
        }
        assertThat(DocsCommand.TOPICS.stream()
                        .map(DocsCommand.Topic::id)
                        .distinct()
                        .count())
                .isEqualTo(DocsCommand.TOPICS.size());
    }

    @Test
    void literalSearchReturnsSourceLineNumbersInCatalogOrder() throws Exception {
        Result result = run("docs", "--search", "BLOCKING SENSOR", "--json");
        assertThat(result.code()).isZero();
        var matches = JsonValue.parse(result.out()).get("matches").values();
        assertThat(matches).isNotEmpty();
        for (var match : matches) {
            assertThat(match.names()).containsExactly("topic", "file", "line", "text");
            var topic = DocsCommand.TOPICS.stream()
                    .filter(page -> page.file().equals(match.get("file").asString("")))
                    .findFirst()
                    .orElseThrow();
            int line = match.get("line").asInt(0);
            assertThat(topic.content().lines().toList().get(line - 1))
                    .isEqualTo(match.get("text").asString(""));
        }
        assertThat(run("docs", "--search", "BLOCKING SENSOR").out()).contains(".md:");
        assertThat(JsonValue.parse(run("docs", "--search", "unlikely-literal-[.*]-needle", "--json")
                                .out())
                        .get("matches")
                        .values())
                .isEmpty();
    }

    @Test
    void invalidArgumentsFailWithoutInventingDocumentation() {
        for (String[] args : new String[][] {
            {"docs", "../CLI.md"},
            {"docs", "--search", " "},
            {"docs", "cli", "--search", "version"},
            {"docs", "cli", "properties"}
        }) {
            Result result = run(args);
            assertThat(result.code()).isEqualTo(1);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).isNotBlank();
        }
    }

    @Test
    void localCommandsAreNotDiagnosticToolsAndAppearInHelp() {
        assertThat(ToolManifest.bundled().tools())
                .noneMatch(tool -> Set.of("docs", "update").contains(tool.path().get(0)));
        assertThat(run("--help").out()).contains("bootui docs", "bootui update");
        assertThat(run("docs", "--help").out().replaceAll("\\s+", " ")).contains("No application or network");
    }

    @Test
    void topicJsonHasDeterministicFieldOrder() {
        assertThat(DocsCommand.TOPICS.get(0).json().names()).containsExactly("id", "title", "description", "file");
    }

    private Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = BootUiCli.run(
                args,
                Map.of("BOOTUI_URL", "not a URL", "BOOTUI_MAVEN_REPO", "http://127.0.0.1:1"),
                false,
                new PrintWriter(out),
                new PrintWriter(err),
                options -> {
                    throw new AssertionError("Offline commands must not construct an application client.");
                });
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
