package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import io.github.jdubois.bootui.client.BootUiInstance;
import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Previews or idempotently writes IDE / AI coding agent MCP configuration for BootUI without ever
 * writing authentication tokens or secrets to disk.
 */
final class InitCommand implements Callable<Integer> {

    static final String WORKSPACE_DIR_ENV = "BOOTUI_WORKSPACE_DIR";
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

    private enum Target {
        VSCODE("vscode", "servers", true),
        CURSOR("cursor", "mcpServers", false),
        CLAUDE("claude", null, true);

        final String id;
        final String containerKey;
        final boolean includeTransportType;

        Target(String id, String containerKey, boolean includeTransportType) {
            this.id = id;
            this.containerKey = containerKey;
            this.includeTransportType = includeTransportType;
        }
    }

    private final CliContext context;
    private boolean vscode;
    private boolean cursor;
    private boolean claude;
    private boolean print;
    private boolean dryRun;

    private InitCommand(CliContext context) {
        this.context = context;
    }

    static void add(CliContext context, CommandSpec root) {
        InitCommand command = new InitCommand(context);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("init");
        CommandTree.configure(context, spec);
        spec.usageMessage()
                .description(
                        "Preview or idempotently write IDE/agent MCP configuration for BootUI.",
                        "Preserves existing MCP servers when merging into .vscode/mcp.json or ~/.cursor/mcp.json, "
                                + "and never writes authentication tokens or headers to disk.")
                .footer(
                        "Examples: bootui init --vscode",
                        "          bootui init --cursor --url http://localhost:8081",
                        "          bootui init --claude --print",
                        "          bootui init --app order-service --vscode");
        spec.addOption(OptionSpec.builder("--vscode")
                .type(boolean.class)
                .description("Write or preview .vscode/mcp.json (default when no client flag is given).")
                .setter(CommandTree.setter((Boolean value) -> command.vscode = Boolean.TRUE.equals(value)))
                .build());
        spec.addOption(OptionSpec.builder("--cursor")
                .type(boolean.class)
                .description("Write or preview ~/.cursor/mcp.json.")
                .setter(CommandTree.setter((Boolean value) -> command.cursor = Boolean.TRUE.equals(value)))
                .build());
        spec.addOption(OptionSpec.builder("--claude")
                .type(boolean.class)
                .description("Print the 'claude mcp add' command for Claude Code.")
                .setter(CommandTree.setter((Boolean value) -> command.claude = Boolean.TRUE.equals(value)))
                .build());
        spec.addOption(OptionSpec.builder("--print")
                .type(boolean.class)
                .description("Print the configuration without writing any file.")
                .setter(CommandTree.setter((Boolean value) -> command.print = Boolean.TRUE.equals(value)))
                .build());
        spec.addOption(OptionSpec.builder("--dry-run")
                .type(boolean.class)
                .description("Alias for --print: preview the configuration without writing any file.")
                .setter(CommandTree.setter((Boolean value) -> command.dryRun = Boolean.TRUE.equals(value)))
                .build());
        root.addSubcommand("init", new CommandLine(spec));
    }

    @Override
    public Integer call() throws IOException {
        try {
            Target target = resolveTarget();
            String mcpUrl = resolveMcpUrl();
            boolean previewOnly = print || dryRun;

            if (target == Target.CLAUDE) {
                String claudeCommand = "claude mcp add --transport http bootui " + mcpUrl;
                if (context.options().explicitJson() && !previewOnly) {
                    Map<String, JsonValue> json = new LinkedHashMap<>();
                    json.put("client", JsonValue.of(target.id));
                    json.put("url", JsonValue.of(mcpUrl));
                    json.put("command", JsonValue.of(claudeCommand));
                    context.out().println(JsonValue.object(json).toJson());
                } else {
                    context.out().println(claudeCommand);
                }
                context.out().flush();
                return ExitCodes.SUCCESS;
            }

            Path configFile = resolveConfigPath(target);
            JsonValue existing = readExistingConfig(configFile);
            JsonValue merged = mergeMcpConfig(existing, target, mcpUrl);
            String rendered = JsonWriter.pretty(merged);

            if (previewOnly) {
                context.out().println(rendered);
                context.out().flush();
                return ExitCodes.SUCCESS;
            }

            writeAtomically(configFile, rendered + System.lineSeparator());
            String displayPath = target == Target.VSCODE ? ".vscode/mcp.json" : configFile.toString();
            if (context.options().explicitJson()) {
                Map<String, JsonValue> json = new LinkedHashMap<>();
                json.put("client", JsonValue.of(target.id));
                json.put("path", JsonValue.of(configFile.toString()));
                json.put("url", JsonValue.of(mcpUrl));
                json.put("written", JsonValue.of(true));
                context.out().println(JsonValue.object(json).toJson());
            } else {
                context.out().println("Wrote BootUI MCP configuration to " + displayPath + " (" + mcpUrl + ").");
            }
            context.out().flush();
            return ExitCodes.SUCCESS;
        } catch (BootUiClientException failure) {
            context.err().println(failure.getMessage());
            context.err().flush();
            return ExitCodes.ERROR;
        }
    }

    private Target resolveTarget() {
        int count = (vscode ? 1 : 0) + (cursor ? 1 : 0) + (claude ? 1 : 0);
        if (count > 1) {
            throw new IllegalArgumentException("Choose one of --vscode, --cursor, or --claude.");
        }
        if (cursor) {
            return Target.CURSOR;
        }
        if (claude) {
            return Target.CLAUDE;
        }
        return Target.VSCODE;
    }

    private String resolveMcpUrl() {
        GlobalOptions options = context.options();
        String baseUrl;
        String apiPath;
        if (options.app() != null) {
            if (options.hasExplicitUrl() || options.hasExplicitApiPath()) {
                throw new BootUiClientException("Choose --app or --url/--api-path, not both.");
            }
            BootUiInstance instance = context.resolveTargetInstance();
            baseUrl = instance.baseUrl();
            apiPath = instance.apiPath();
        } else {
            // Deliberately ignore any token from --token or $BOOTUI_TOKEN so credentials are never written.
            BootUiClientOptions resolved = options.toClientOptions(context.environment());
            baseUrl = validateBaseUrl(resolved.baseUrl());
            apiPath = validateApiPath(resolved.apiPath());
        }
        return new BootUiClientOptions(baseUrl, apiPath, null, BootUiClientOptions.DEFAULT_TIMEOUT).apiEndpoint("/mcp");
    }

    private static String validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid --url: " + baseUrl, invalid);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null
                || (!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Invalid --url: must be an http:// or https:// URL without credentials or query parameters.");
        }
        return baseUrl;
    }

    private static String validateApiPath(String apiPath) {
        if (apiPath == null
                || !apiPath.startsWith("/")
                || apiPath.contains("..")
                || apiPath.contains("\\")
                || apiPath.contains("?")
                || apiPath.contains("#")) {
            throw new IllegalArgumentException("Invalid --api-path: " + apiPath);
        }
        return apiPath;
    }

    private Path resolveConfigPath(Target target) throws IOException {
        if (target == Target.VSCODE) {
            String configuredWorkspace = context.environment().get(WORKSPACE_DIR_ENV);
            Path workspace = (configuredWorkspace != null && !configuredWorkspace.isBlank())
                    ? Path.of(configuredWorkspace.trim()).toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath().normalize();
            return workspace.resolve(".vscode").resolve("mcp.json");
        }
        String home = context.environment().get("HOME");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        if (home == null || home.isBlank()) {
            throw new IOException("Cannot resolve user home directory for ~/.cursor/mcp.json.");
        }
        return Path.of(home.trim())
                .toAbsolutePath()
                .normalize()
                .resolve(".cursor")
                .resolve("mcp.json");
    }

    private static JsonValue readExistingConfig(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to read non-regular or symbolic link MCP config file: " + path);
        }
        long size = Files.size(path);
        if (size > MAX_CONFIG_BYTES) {
            throw new IOException("Existing MCP configuration exceeds " + MAX_CONFIG_BYTES + " bytes: " + path);
        }
        String content;
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_CONFIG_BYTES + 1);
            if (bytes.length > MAX_CONFIG_BYTES) {
                throw new IOException("Existing MCP configuration exceeds " + MAX_CONFIG_BYTES + " bytes: " + path);
            }
            content = new String(bytes, StandardCharsets.UTF_8);
        }
        if (content.isBlank()) {
            return null;
        }
        JsonValue parsed;
        try {
            parsed = JsonValue.parseStrict(content);
        } catch (JsonParseException invalid) {
            throw new IOException(
                    "Existing MCP configuration at " + path + " is not valid JSON: " + invalid.getMessage(), invalid);
        }
        if (!parsed.isObject()) {
            throw new IOException("Existing MCP configuration at " + path + " must be a JSON object.");
        }
        return parsed;
    }

    private static JsonValue mergeMcpConfig(JsonValue existing, Target target, String mcpUrl) throws IOException {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        Map<String, JsonValue> servers = new LinkedHashMap<>();

        if (existing != null) {
            for (String key : existing.names()) {
                JsonValue value = existing.get(key);
                if (target.containerKey.equals(key)) {
                    if (!value.isNull() && !value.isObject()) {
                        throw new IOException(
                                "Existing '" + target.containerKey + "' property in MCP config must be a JSON object.");
                    }
                    if (value.isObject()) {
                        for (String serverName : value.names()) {
                            servers.put(serverName, value.get(serverName));
                        }
                    }
                } else {
                    root.put(key, value);
                }
            }
        }

        // Build a clean BootUI server entry with no headers or tokens.
        Map<String, JsonValue> bootUiServer = new LinkedHashMap<>();
        if (target.includeTransportType) {
            bootUiServer.put("type", JsonValue.of("http"));
        }
        bootUiServer.put("url", JsonValue.of(mcpUrl));
        servers.put("bootui", JsonValue.object(bootUiServer));
        root.put(target.containerKey, JsonValue.object(servers));
        return JsonValue.object(root);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("MCP config file has no parent directory: " + target);
        }
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(parent)) {
            throw new IOException("Refusing to write inside symbolic link directory: " + parent);
        }
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to overwrite symbolic link: " + target);
        }
        Path temp = Files.createTempFile(parent, ".bootui-init-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
