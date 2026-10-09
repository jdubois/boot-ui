package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.cli.CliCommandPaths;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the checked-in command manifest honest.
 *
 * <p>This is the test that makes the CLI a projection rather than a copy. A tool added to
 * {@code McpToolCatalog} without a command path fails here, and so does a manifest that was not regenerated
 * after the catalog changed — so the CLI cannot silently fall behind the MCP registry.
 *
 * <p>Run with {@code -Dbootui.manifest.write=true} to rewrite the checked-in file instead of failing.
 */
class ToolManifestGeneratorTests {

    private static final Path MANIFEST = Path.of("src/main/resources/bootui-tools.json");

    @Test
    void everyCatalogToolHasACommandPath() {
        Set<String> catalogNames = McpToolCatalog.names();
        Set<String> mapped = CliCommandPaths.BY_TOOL.keySet();

        assertThat(mapped)
                .as("every MCP tool must be reachable from the CLI; add it to CliCommandPaths")
                .containsExactlyInAnyOrderElementsOf(catalogNames);
    }

    @Test
    void advisorDetailsProjectRequiredIdSnapshotAndPagingFlags() {
        ToolManifest manifest = ToolManifest.parse(ToolManifestGenerator.generate());
        List<ToolManifest.Tool> tools = manifest.tools().stream()
                .filter(tool -> tool.schema().equals("RULE_VIOLATIONS"))
                .toList();
        assertThat(tools).hasSize(7).allSatisfy(tool -> {
            assertThat(tool.takesId()).isTrue();
            assertThat(tool.takesScanId()).isTrue();
            assertThat(tool.takesOffset()).isTrue();
            assertThat(tool.takesLimit()).isTrue();
            assertThat(tool.takesQuery()).isFalse();
            assertThat(tool.command()).endsWith(" violations");
            assertThat(tool.action()).isFalse();
        });
    }

    @Test
    void agentStatusTakesAQueryButNoLimitBecauseItListsEveryMatchingSensor() {
        ToolManifest.Tool agentStatus = ToolManifest.parse(ToolManifestGenerator.generate()).tools().stream()
                .filter(tool -> tool.name().equals("get_agent_status"))
                .findFirst()
                .orElseThrow();
        assertThat(agentStatus.schema()).isEqualTo("QUERY");
        assertThat(agentStatus.takesQuery()).isTrue();
        assertThat(agentStatus.takesLimit()).isFalse();
        assertThat(agentStatus.description()).contains("overflow", "another request's context");
        assertThat(ToolManifest.bundled().byName("get_agent_status").description())
                .isEqualTo(agentStatus.description());
    }

    @Test
    void noTwoToolsShareACommandPath() {
        Set<String> paths = new HashSet<>(CliCommandPaths.BY_TOOL.values());

        assertThat(paths).hasSize(CliCommandPaths.BY_TOOL.size());
    }

    @Test
    void noCommandPathIsAPrefixOfAnotherSoEveryLeafIsReachable() {
        // "bootui traces" cannot be both a command and the parent of "bootui traces clear": picocli would
        // have to choose, and one of the two tools would become uninvokable.
        List<String> paths = CliCommandPaths.BY_TOOL.values().stream().sorted().toList();

        for (String path : paths) {
            List<String> shadowed = paths.stream()
                    .filter(other -> !other.equals(path) && other.startsWith(path + " "))
                    .toList();
            assertThat(shadowed)
                    .as("'%s' is both a command and a command group", path)
                    .isEmpty();
        }
    }

    @Test
    void noCommandPathShadowsABuiltInCommand() {
        // 'tools' and 'mcp' are the CLI's own commands; a tool claiming one of them would make it
        // unreachable, and picocli would only tell us at startup.
        assertThat(CliCommandPaths.BY_TOOL.values())
                .noneMatch(path -> path.equals("tools")
                        || path.equals("mcp")
                        || path.startsWith("tools ")
                        || path.startsWith("mcp "));
    }

    @Test
    void commandPathsUseOnlyCharactersAShellDoesNotNeedQuotedFor() {
        assertThat(CliCommandPaths.BY_TOOL.values())
                .allSatisfy(path -> assertThat(path).matches("[a-z0-9]+(-[a-z0-9]+)*( [a-z0-9]+(-[a-z0-9]+)*)*"));
    }

    @Test
    void theCheckedInManifestMatchesTheCatalog() throws IOException {
        String generated = ToolManifestGenerator.generate();

        if (Boolean.getBoolean("bootui.manifest.write")) {
            Files.writeString(MANIFEST, generated, StandardCharsets.UTF_8);
        }

        assertThat(Files.readString(MANIFEST, StandardCharsets.UTF_8))
                .as("bootui-tools.json is stale; regenerate with "
                        + "./mvnw -pl bootui-cli test -Dtest=ToolManifestGeneratorTests -Dbootui.manifest.write=true")
                .isEqualTo(generated);
    }

    @Test
    void theBundledManifestDescribesEveryToolTheCatalogDeclares() {
        ToolManifest manifest = ToolManifest.bundled();

        assertThat(manifest.tools().stream().map(ToolManifest.Tool::name).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.names());
        assertThat(manifest.tools()).allSatisfy(tool -> {
            McpToolCatalog.Entry entry = McpToolCatalog.byName(tool.name()).orElseThrow();
            assertThat(tool.schema()).isEqualTo(entry.schema().name());
            assertThat(tool.panel()).isEqualTo(entry.panelId());
            assertThat(tool.action()).isEqualTo(entry.action());
            assertThat(tool.summary()).isNotBlank();
            assertThat(tool.stacks())
                    .containsExactlyInAnyOrderElementsOf(
                            entry.stacks().stream().map(Enum::name).toList());
        });
    }

    @Test
    void theManifestRecordsStackSpecificToolsSoHelpCanSaySo() {
        ToolManifest manifest = ToolManifest.bundled();

        assertThat(manifest.byName("get_http_sessions").stacks())
                .as("Spring WebFlux has no HTTP sessions to report")
                .doesNotContain("SPRING_WEBFLUX");
        assertThat(manifest.byName("get_overview").onEveryStack()).isTrue();
    }
}
