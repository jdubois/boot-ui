package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Offline guards for the portable Agent Plugins payload consumed by Cursor and other compatible
 * clients. The engine deliberately has no JSON dependency, so these tests inspect the small
 * checked-in manifests directly and pin the schema rules that matter to BootUI.
 */
class AgentPluginPayloadTests {

    private static final String PLUGIN_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json";

    private static final String MCP_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json";

    private static final Set<String> PLUGIN_FIELDS = Set.of(
            "$schema",
            "name",
            "version",
            "description",
            "author",
            "homepage",
            "repository",
            "license",
            "keywords",
            "extensions");

    private static final Set<String> MCP_FIELDS = Set.of("$schema", "mcpServers");

    private static final Set<String> AUTHOR_FIELDS = Set.of("name", "email", "url");

    private static final Set<String> REMOTE_SERVER_FIELDS = Set.of("type", "url", "headers");

    @Test
    void agentPluginManifestUsesTheClosedStandardSchema() throws IOException {
        Path pluginDirectory = RepositoryFiles.file("plugins/bootui");
        String manifest = Files.readString(pluginDirectory.resolve("plugin.json"));

        assertJsonObject(manifest);
        assertThat(topLevelFields(manifest))
                .as("Agent Plugins 1.0.0 has a closed top-level schema")
                .isSubsetOf(PLUGIN_FIELDS);
        assertThat(stringValue(manifest, "$schema")).isEqualTo(PLUGIN_SCHEMA);
        assertThat(stringValue(manifest, "name"))
                .isEqualTo(pluginDirectory.getFileName().toString())
                .matches("^(?!.*(?:--|\\.\\.))[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$");
        assertThat(topLevelFields(objectValue(manifest, "author")))
                .as("Agent Plugins 1.0.0 has a closed author schema")
                .isSubsetOf(AUTHOR_FIELDS);
        assertThat(manifest)
                .as("the portable manifest must not use Claude Code-only fields")
                .doesNotContain("\"displayName\"", "\"mcpServers\"");
        assertThat(pluginDirectory.resolve("skills/bootui/SKILL.md")).exists();
        assertThat(pluginDirectory.resolve("README.md")).exists();
    }

    @Test
    void mcpManifestDeclaresTheMatchingStreamableHttpLoopbackServer() throws IOException {
        String plugin = Files.readString(RepositoryFiles.file("plugins/bootui/plugin.json"));
        String mcp = Files.readString(RepositoryFiles.file("plugins/bootui/mcp.json"));

        assertJsonObject(mcp);
        assertThat(topLevelFields(mcp))
                .as("Agent Plugins 1.0.0 mcp.json permits only its schema and server map")
                .containsExactlyInAnyOrderElementsOf(MCP_FIELDS);
        assertThat(stringValue(mcp, "$schema")).isEqualTo(MCP_SCHEMA);
        assertThat(schemaVersion(stringValue(mcp, "$schema"))).isEqualTo(schemaVersion(stringValue(plugin, "$schema")));
        assertThat(topLevelFields(objectValue(objectValue(mcp, "mcpServers"), "bootui")))
                .as("a Streamable HTTP server permits only type, url, and optional headers")
                .isSubsetOf(REMOTE_SERVER_FIELDS);
        assertThat(stringValue(mcp, "type")).isEqualTo("streamable-http");

        String url = stringValue(mcp, "url");
        assertThat(url)
                .as("the Agent Plugins standard forbids placeholder expansion in remote URLs")
                .doesNotContain("${");
        URI endpoint = URI.create(url);
        assertThat(endpoint.getScheme()).isEqualTo("http");
        assertThat(endpoint.getHost()).isIn("localhost", "127.0.0.1", "::1");
        assertThat(endpoint.getUserInfo()).isNull();
        assertThat(endpoint.getFragment()).isNull();
        assertThat(endpoint.getPath()).isEqualTo("/bootui/api/mcp");
        assertThat(mcp).as("the portable payload must not embed credentials").doesNotContain("\"headers\"", "\"env\"");
    }

    @Test
    void cursorMarketplacePointsAtThePortablePlugin() throws IOException {
        String marketplace = Files.readString(RepositoryFiles.file(".cursor-plugin/marketplace.json"));
        String source = stringValue(marketplace, "source");
        Path plugin = RepositoryFiles.root().resolve(source).normalize();

        assertJsonObject(marketplace);
        assertThat(plugin).startsWith(RepositoryFiles.root());
        assertThat(plugin.resolve("plugin.json")).exists();
        assertThat(plugin.resolve("mcp.json")).exists();
        assertThat(plugin.resolve("skills/bootui/SKILL.md")).exists();
    }

    private static void assertJsonObject(String contents) {
        assertThat(contents.strip())
                .as("the manifest must be a JSON object")
                .startsWith("{")
                .endsWith("}");
        assertThat(contents)
                .as("the manifest must not contain a trailing JSON comma")
                .doesNotContainPattern(",\\s*[}\\]]");
        assertThat(topLevelFields(contents))
                .as("the manifest must contain at least one JSON object field")
                .isNotEmpty();
    }

    private static Set<String> topLevelFields(String contents) {
        Set<String> fields = new LinkedHashSet<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int stringStart = -1;
        String lastString = null;
        for (int index = 0; index < contents.length(); index++) {
            char current = contents.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                    lastString = contents.substring(stringStart, index);
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                stringStart = index + 1;
            } else if (current == '{' || current == '[') {
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
            } else if (current == ':' && depth == 1 && lastString != null) {
                fields.add(lastString);
                lastString = null;
            } else if (!Character.isWhitespace(current) && current != ',') {
                lastString = null;
            }
        }
        return fields;
    }

    private static String stringValue(String contents, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"(?<value>[^\"]+)\"")
                .matcher(contents);
        assertThat(matcher.find())
                .as("the manifest declares string field %s", field)
                .isTrue();
        return matcher.group("value");
    }

    private static String objectValue(String contents, String field) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\{").matcher(contents);
        assertThat(matcher.find())
                .as("the manifest declares object field %s", field)
                .isTrue();

        int start = matcher.end() - 1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < contents.length(); index++) {
            char current = contents.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return contents.substring(start, index + 1);
            }
        }
        throw new AssertionError(field + " is not a complete JSON object");
    }

    private static String schemaVersion(String schema) {
        Matcher matcher = Pattern.compile("/schemas/(?<version>[^/]+)/").matcher(schema);
        assertThat(matcher.find()).as("%s carries a schema version", schema).isTrue();
        return matcher.group("version");
    }
}
