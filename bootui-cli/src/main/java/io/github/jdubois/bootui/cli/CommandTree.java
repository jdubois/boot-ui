package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

final class CommandTree {

    private CommandTree() {}

    static CommandSpec build(CliContext context) {
        CommandSpec root = group(context, "bootui", "Ask a running Spring Boot or Quarkus application a question.");
        root.usageMessage()
                .description(
                        "Ask a running Spring Boot or Quarkus application a question.",
                        "",
                        "Diagnostic commands are one-to-one projections of BootUI MCP tools. Point --url at the application "
                                + "(default http://localhost:8080) and it answers over its local-only HTTP endpoint.",
                        "Output is JSON when piped or with --json, and readable otherwise.",
                        "",
                        "The 'docs', 'update', 'ci', 'init', and 'instances' commands are client-side exceptions, not MCP tools.")
                .footer(
                        "",
                        "Exit codes: 0 answered, 1 usage or transport error, 2 refused by BootUI's panel policy, "
                                + "3 --fail-on gate met.",
                        "Run 'bootui tools' to see what a specific application actually exposes.",
                        "CI aggregate exits: 0 pass, 1 not assessed/error, 3 policy violation.");

        List<CommandSpec> toolGroups = new ArrayList<>();
        for (ToolManifest.Tool tool : context.manifest().tools()) {
            List<String> path = tool.path();
            CommandSpec parent = root;
            for (int i = 0; i < path.size() - 1; i++) {
                parent = childGroup(context, parent, path.get(i), toolGroups);
            }
            parent.addSubcommand(path.get(path.size() - 1), new CommandLine(toolCommand(context, tool)));
        }

        for (CommandSpec group : toolGroups) {
            group.usageMessage()
                    .description(String.join(
                            ", ",
                            group.subcommands().keySet().stream()
                                    .filter(name -> !name.startsWith("-"))
                                    .toList()));
            String prefix = String.join(" ", groupPath(group)) + " ";
            listCommands(
                    group,
                    context.manifest().tools().stream()
                            .filter(tool -> tool.command().startsWith(prefix))
                            .toList(),
                    false);
        }

        root.addSubcommand("tools", new CommandLine(toolsCommand(context)));
        root.addSubcommand("mcp", new CommandLine(mcpCommand(context)));
        root.addSubcommand("instances", new CommandLine(instancesCommand(context)));
        InitCommand.add(context, root);
        CiCommands.add(context, root);
        DocsCommand.add(context, root);
        UpdateCommand.add(context, root);
        listCommands(root, context.manifest().tools(), true);
        return root;
    }

    private static List<String> groupPath(CommandSpec group) {
        List<String> words = new ArrayList<>();
        for (CommandSpec spec = group; spec.parent() != null; spec = spec.parent()) {
            words.add(0, spec.name());
        }
        return words;
    }

    private static void listCommands(CommandSpec spec, List<ToolManifest.Tool> tools, boolean root) {
        Map<String, CommandLine.IHelpSectionRenderer> sections =
                new LinkedHashMap<>(spec.usageMessage().sectionMap());
        sections.put(CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING, help -> "");
        sections.put(
                CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
                help -> HelpListing.render(
                        tools, root, help.commandSpec().usageMessage().width()));
        spec.usageMessage().sectionMap(sections);
    }

    private static CommandSpec childGroup(
            CliContext context, CommandSpec parent, String name, List<CommandSpec> groups) {
        CommandLine existing = parent.subcommands().get(name);
        if (existing != null) {
            return existing.getCommandSpec();
        }
        CommandSpec child = group(context, name, "");
        parent.addSubcommand(name, new CommandLine(child));
        groups.add(child);
        return child;
    }

    private static CommandSpec group(CliContext context, String name, String description) {
        UsageCommand usage = new UsageCommand();
        CommandSpec spec = CommandSpec.wrapWithoutInspection(usage).name(name);
        usage.spec = spec;
        spec.usageMessage().description(description);
        configure(context, spec);
        return spec;
    }

    private static CommandSpec toolCommand(CliContext context, ToolManifest.Tool tool) {
        ToolCommand command = new ToolCommand(context, tool);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command)
                .name(tool.path().get(tool.path().size() - 1));
        spec.usageMessage()
                .description(HelpListing.literal(tool.helpDescription()))
                .footer(
                        "",
                        HelpListing.literal("Example: " + tool.example()),
                        "MCP tool: " + tool.name() + stackNote(tool));
        if (tool.takesQuery()) {
            spec.addOption(OptionSpec.builder("-q", "--query")
                    .paramLabel("<text>")
                    .type(String.class)
                    .description(
                            tool.queryHelp().isEmpty()
                                    ? "Filter the results, matched case-insensitively by the application."
                                    : HelpListing.literal("Filter the results: " + tool.queryHelp() + "."))
                    .setter(setter((String value) -> command.query = value))
                    .build());
        }
        if (tool.takesLimit()) {
            spec.addOption(OptionSpec.builder("-n", "--limit")
                    .paramLabel("<count>")
                    .type(Integer.class)
                    .description("Return at most this many results; the application caps it at its own maximum.")
                    .setter(setter((Integer value) -> command.limit = value))
                    .build());
        }
        if (tool.ignoresLimit()) {
            spec.addOption(OptionSpec.builder("-n", "--limit")
                    .paramLabel("<count>")
                    .type(Integer.class)
                    .hidden(true)
                    .description("Ignored: this command lists every match.")
                    .setter(setter((Integer value) -> {}))
                    .build());
        }
        if (tool.takesId()) {
            spec.addPositional(PositionalParamSpec.builder()
                    .paramLabel("<id>")
                    .index("0")
                    .arity(tool.optionalId() ? "0..1" : "1")
                    .required(!tool.optionalId())
                    .type(String.class)
                    .description(
                            !tool.idHelp().isEmpty()
                                    ? HelpListing.literal(tool.idHelp())
                                    : tool.optionalId()
                                            ? "Run id to compare with (default: previous)."
                                            : "The identifier of the resource to read.")
                    .setter(setter((String value) -> command.id = value))
                    .build());
        }
        if (tool.takesScanId()) {
            spec.addOption(OptionSpec.builder("--scan-id")
                    .paramLabel("<scanId>")
                    .type(String.class)
                    .required(true)
                    .description("The cached report's violationDetails.scanId; does not start a scan.")
                    .setter(setter((String value) -> command.scanId = value))
                    .build());
        }
        if (tool.takesOffset()) {
            spec.addOption(OptionSpec.builder("--offset")
                    .paramLabel("<offset>")
                    .type(Integer.class)
                    .description("Zero-based retained violation offset (default 0).")
                    .setter(setter((Integer value) -> command.offset = value))
                    .build());
        }
        if (FailOnTools.supports(tool.name())) {
            spec.addOption(OptionSpec.builder("--fail-on")
                    .paramLabel(FailOnTools.supportsInsufficient(tool.name()) ? "<criterion>" : "<severity>")
                    .type(FailOn.Criterion.class)
                    .description(
                            FailOnTools.supportsInsufficient(tool.name())
                                    ? "Use insufficient to exit 3 when evidence is insufficient or partial; exits 1 when "
                                            + "evidence cannot be evaluated. The tool response is not rewritten."
                                            + ("get_runtime_insights".equals(tool.name())
                                                    ? " When --query is omitted, the gate checks all observation kinds."
                                                    : "")
                                    : "Exit 3 when a retained finding is at or above this severity ("
                                            + FailOn.accepted()
                                            + "). Exits 1 when the scan cannot be evaluated, such as a report that was "
                                            + "never scanned. The output is unchanged.")
                    .converters(value -> criterion(tool.name(), value))
                    .setter(setter((FailOn.Criterion value) -> command.failOn = value))
                    .build());
        }
        configure(context, spec);
        return spec;
    }

    private static FailOn.Criterion criterion(String toolName, String value) {
        try {
            return FailOn.parse(toolName, value);
        } catch (IllegalArgumentException invalid) {
            throw new CommandLine.TypeConversionException(invalid.getMessage());
        }
    }

    static String stackNote(ToolManifest.Tool tool) {
        if (tool.onEveryStack()) {
            return "";
        }
        String stacks =
                String.join(", ", tool.stacks()).toLowerCase(Locale.ROOT).replace('_', ' ');
        return " (only on " + stacks + ")";
    }

    private static CommandSpec toolsCommand(CliContext context) {
        Callable<Integer> command = context::listTools;
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("tools");
        spec.usageMessage()
                .description("List the tools this application exposes, and whether its panels allow each one.");
        configure(context, spec);
        return spec;
    }

    private static CommandSpec instancesCommand(CliContext context) {
        Callable<Integer> command = context::listInstances;
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("instances");
        spec.usageMessage()
                .description(
                        "List running local BootUI instances discovered in ~/.bootui/instances.",
                        "Stale leases whose process has exited or whose start timestamp does not match are pruned automatically.")
                .footer(
                        "Examples: bootui instances",
                        "          bootui instances --json",
                        "          bootui --app my-service overview");
        configure(context, spec);
        return spec;
    }

    private static CommandSpec mcpCommand(CliContext context) {
        CommandSpec spec = group(context, "mcp", "Inspect and toggle the application's MCP server.");

        CommandSpec status =
                leaf(context, "status", "Show the MCP server state and advertised tools.", context::mcpStatus);
        CommandSpec enable = leaf(
                context,
                "enable",
                "Turn the MCP server on for this running application.",
                () -> context.mcpToggle(true));
        CommandSpec disable = leaf(
                context,
                "disable",
                "Turn the MCP server off for this running application.",
                () -> context.mcpToggle(false));

        spec.addSubcommand("status", new CommandLine(status));
        spec.addSubcommand("enable", new CommandLine(enable));
        spec.addSubcommand("disable", new CommandLine(disable));
        return spec;
    }

    private static CommandSpec leaf(CliContext context, String name, String description, Callable<Integer> action) {
        CommandSpec spec = CommandSpec.wrapWithoutInspection(action).name(name);
        spec.usageMessage().description(description);
        configure(context, spec);
        return spec;
    }

    static void configure(CliContext context, CommandSpec spec) {
        GlobalOptions options = context.options();
        spec.mixinStandardHelpOptions(true);
        spec.exitCodeOnInvalidInput(ExitCodes.ERROR);
        spec.exitCodeOnExecutionException(ExitCodes.ERROR);
        spec.usageMessage().autoWidth(true);

        Map<String, OptionSpec> globals = new LinkedHashMap<>();
        globals.put(
                "--url",
                OptionSpec.builder("--url")
                        .paramLabel("<url>")
                        .type(String.class)
                        .description("Base URL of the application. Default http://localhost:8080, or $BOOTUI_URL.")
                        .setter(setter(options::setUrl))
                        .build());
        globals.put(
                "--app",
                OptionSpec.builder("--app")
                        .paramLabel("<name>")
                        .type(String.class)
                        .description("Target a running local application by application name or instanceId prefix.")
                        .setter(setter(options::setApp))
                        .build());
        globals.put(
                "--api-path",
                OptionSpec.builder("--api-path")
                        .paramLabel("<path>")
                        .type(String.class)
                        .description("BootUI API path when bootui.api-path is customised. "
                                + "Default /bootui/api, or $BOOTUI_API_PATH.")
                        .setter(setter(options::setApiPath))
                        .build());
        globals.put(
                "--token",
                OptionSpec.builder("--token")
                        .paramLabel("<token>")
                        .type(String.class)
                        .description(
                                "Sent as an Authorization header when bootui.authentication.token is set. Or $BOOTUI_TOKEN.")
                        .setter(setter(options::setToken))
                        .build());
        globals.put(
                "--timeout",
                OptionSpec.builder("--timeout")
                        .paramLabel("<seconds>")
                        .type(Integer.class)
                        .description("How long to wait for an answer. Default 60.")
                        .converters(CommandTree::positiveSeconds)
                        .setter(setter(options::setTimeoutSeconds))
                        .build());
        globals.put(
                "--json",
                OptionSpec.builder("--json")
                        .type(boolean.class)
                        .description(
                                "docs".equals(spec.name()) || "init".equals(spec.name())
                                        ? "Print structured JSON instead of raw Markdown or text. Never implied when piped."
                                        : "update".equals(spec.name())
                                                ? "Print structured update status. Implied when output is not a terminal."
                                                : "Print the application's JSON verbatim. Implied when output is not a terminal.")
                        .setter(booleanSetter(options::setJson))
                        .build());
        globals.put(
                "--no-color",
                OptionSpec.builder("--no-color")
                        .type(boolean.class)
                        .description("Disable ANSI colour. Also honours $NO_COLOR.")
                        .setter(booleanSetter(options::setNoColor))
                        .build());
        globals.put(
                "--verbose",
                OptionSpec.builder("-v", "--verbose")
                        .type(boolean.class)
                        .description("Print the underlying failure when a request does not complete.")
                        .setter(booleanSetter(options::setVerbose))
                        .build());

        for (OptionSpec option : globals.values()) {
            if (("docs".equals(spec.name()) || "update".equals(spec.name()) || "instances".equals(spec.name()))
                    && List.of("--url", "--app", "--api-path", "--token", "--timeout")
                            .contains(option.longestName())) {
                continue;
            }
            spec.addOption(option);
        }
    }

    private static Integer positiveSeconds(String value) {
        int seconds;
        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new CommandLine.TypeConversionException("'" + value + "' is not a number of seconds");
        }
        if (seconds <= 0) {
            throw new CommandLine.TypeConversionException("must be a positive number of seconds, not " + seconds);
        }
        return seconds;
    }

    static <T> CommandLine.Model.ISetter setter(Consumer<T> consumer) {
        return new CommandLine.Model.ISetter() {
            @Override
            @SuppressWarnings("unchecked")
            public <V> V set(V value) {
                if (value != null) {
                    consumer.accept((T) value);
                }
                return null;
            }
        };
    }

    private static CommandLine.Model.ISetter booleanSetter(Consumer<Boolean> consumer) {
        return new CommandLine.Model.ISetter() {
            @Override
            @SuppressWarnings("unchecked")
            public <V> V set(V value) {
                if (Boolean.TRUE.equals(value)) {
                    consumer.accept(true);
                }
                return null;
            }
        };
    }

    private static final class UsageCommand implements Callable<Integer> {

        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return ExitCodes.ERROR;
        }
    }

    private static final class ToolCommand implements Callable<Integer> {

        private final CliContext context;
        private final ToolManifest.Tool tool;

        private String query;
        private Integer limit;
        private String id;
        private String scanId;
        private Integer offset;
        private FailOn.Criterion failOn;

        ToolCommand(CliContext context, ToolManifest.Tool tool) {
            this.context = context;
            this.tool = tool;
        }

        @Override
        public Integer call() {
            return context.invokeTool(tool, query, limit, id, scanId, offset, failOn);
        }
    }
}

final class InitCommand implements Callable<Integer> {

    static final String WORKSPACE_DIR_ENV = "BOOTUI_WORKSPACE_DIR";
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

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
            if ((vscode ? 1 : 0) + (cursor ? 1 : 0) + (claude ? 1 : 0) > 1) {
                throw new IllegalArgumentException("Choose one of --vscode, --cursor, or --claude.");
            }
            String target = cursor ? "cursor" : claude ? "claude" : "vscode";
            String mcpUrl = resolveMcpUrl();
            boolean previewOnly = print || dryRun;

            if (claude) {
                String claudeCommand = "claude mcp add --transport http bootui " + mcpUrl;
                if (context.options().explicitJson() && !previewOnly) {
                    Map<String, JsonValue> json = new LinkedHashMap<>();
                    json.put("client", JsonValue.of(target));
                    json.put("url", JsonValue.of(mcpUrl));
                    json.put("command", JsonValue.of(claudeCommand));
                    context.out().println(JsonValue.object(json).toJson());
                } else {
                    context.out().println(claudeCommand);
                }
                context.out().flush();
                return ExitCodes.SUCCESS;
            }

            Path configFile = resolveConfigPath(cursor);
            JsonValue existing = readExistingConfig(configFile);
            JsonValue merged = mergeMcpConfig(existing, cursor ? "mcpServers" : "servers", !cursor, mcpUrl);
            String rendered = JsonWriter.pretty(merged);

            if (previewOnly) {
                context.out().println(rendered);
                context.out().flush();
                return ExitCodes.SUCCESS;
            }

            writeAtomically(configFile, rendered + System.lineSeparator());
            String displayPath = cursor ? configFile.toString() : ".vscode/mcp.json";
            if (context.options().explicitJson()) {
                Map<String, JsonValue> json = new LinkedHashMap<>();
                json.put("client", JsonValue.of(target));
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

    private String resolveMcpUrl() {
        GlobalOptions options = context.options();
        String baseUrl;
        String apiPath;
        if (options.app() != null) {
            if (options.hasExplicitUrl() || options.hasExplicitApiPath()) {
                throw new BootUiClientException("Choose --app or --url/--api-path, not both.");
            }
            BootUiInstances.Instance instance = context.resolveTargetInstance();
            baseUrl = instance.baseUrl();
            apiPath = instance.apiPath();
        } else {
            BootUiClientOptions resolved = options.toClientOptions(context.environment());
            baseUrl = validateBaseUrl(resolved.baseUrl());
            apiPath = BootUiInstances.validateApiPath(resolved.apiPath());
            if (apiPath == null) {
                throw new IllegalArgumentException("Invalid --api-path: " + resolved.apiPath());
            }
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

    private Path resolveConfigPath(boolean cursorTarget) throws IOException {
        if (!cursorTarget) {
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

    private static JsonValue mergeMcpConfig(
            JsonValue existing, String containerKey, boolean includeTransportType, String mcpUrl) throws IOException {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        Map<String, JsonValue> servers = new LinkedHashMap<>();

        if (existing != null) {
            for (String key : existing.names()) {
                JsonValue value = existing.get(key);
                if (containerKey.equals(key)) {
                    if (!value.isNull() && !value.isObject()) {
                        throw new IOException(
                                "Existing '" + containerKey + "' property in MCP config must be a JSON object.");
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

        Map<String, JsonValue> bootUiServer = new LinkedHashMap<>();
        if (includeTransportType) {
            bootUiServer.put("type", JsonValue.of("http"));
        }
        bootUiServer.put("url", JsonValue.of(mcpUrl));
        servers.put("bootui", JsonValue.object(bootUiServer));
        root.put(containerKey, JsonValue.object(servers));
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

final class BootUiInstances {

    static final String BOOTUI_HOME_ENV = "BOOTUI_HOME";
    static final String BOOTUI_INSTANCES_DIR_ENV = "BOOTUI_INSTANCES_DIR";
    static final String INSTANCES_DIR_PROPERTY = "bootui.cli.instances-dir";
    static final long PROCESS_START_TOLERANCE_MILLIS = 2_000L;

    private static final Pattern SAFE_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");
    private static final int MAX_LEASE_BYTES = 16 * 1024;
    private static final int MAX_OVERVIEW_BYTES = 64 * 1024;
    private static final Duration MAX_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    record Instance(
            String instanceId,
            String runId,
            long pid,
            long processStartEpochMillis,
            String appName,
            String framework,
            String baseUrl,
            String apiPath,
            long startedAt) {

        JsonValue toJsonValue() {
            Map<String, JsonValue> fields = new LinkedHashMap<>();
            fields.put("instanceId", JsonValue.of(instanceId));
            fields.put("runId", JsonValue.of(runId));
            fields.put("appName", JsonValue.of(appName));
            fields.put("framework", JsonValue.of(framework));
            fields.put("pid", JsonValue.of(pid));
            fields.put("baseUrl", JsonValue.of(baseUrl));
            fields.put("apiPath", JsonValue.of(apiPath));
            fields.put("startedAt", JsonValue.of(startedAt));
            return JsonValue.object(fields);
        }
    }

    private BootUiInstances() {}

    static Path resolveInstancesDir(Map<String, String> environment) {
        String envInstancesDir = environment == null ? null : environment.get(BOOTUI_INSTANCES_DIR_ENV);
        if (envInstancesDir != null && !envInstancesDir.isBlank()) {
            return Path.of(envInstancesDir.trim()).toAbsolutePath().normalize();
        }
        String sysProp = System.getProperty(INSTANCES_DIR_PROPERTY);
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp.trim()).toAbsolutePath().normalize();
        }
        String bootUiHome = environment == null ? null : environment.get(BOOTUI_HOME_ENV);
        if (bootUiHome != null && !bootUiHome.isBlank()) {
            return Path.of(bootUiHome.trim(), "instances").toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome.trim(), ".bootui", "instances")
                    .toAbsolutePath()
                    .normalize();
        }
        return null;
    }

    static List<Instance> list(Path instancesDir) {
        if (instancesDir == null
                || !Files.exists(instancesDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(instancesDir)
                || !Files.isDirectory(instancesDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Instance> live = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(instancesDir)) {
            for (Path entry : stream) {
                Instance instance = readAndVerifyLease(entry);
                if (instance != null) {
                    live.add(instance);
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        live.sort(Comparator.comparing(Instance::appName)
                .thenComparing(Instance::baseUrl)
                .thenComparing(Instance::instanceId));
        return List.copyOf(live);
    }

    static Instance resolve(
            Path instancesDir,
            String selector,
            Duration timeout,
            Function<BootUiClientOptions, BootUiClient> clientFactory) {
        Objects.requireNonNull(clientFactory, "clientFactory");
        if (selector == null || selector.isBlank()) {
            throw new BootUiClientException("--app must not be blank.");
        }
        String trimmed = selector.trim();
        List<Instance> live = list(instancesDir);
        List<Instance> byAppName = live.stream()
                .filter(instance -> instance.appName().equalsIgnoreCase(trimmed))
                .toList();
        List<Instance> matches = !byAppName.isEmpty()
                ? byAppName
                : live.stream()
                        .filter(instance -> instance.instanceId()
                                .toLowerCase(Locale.ROOT)
                                .startsWith(trimmed.toLowerCase(Locale.ROOT)))
                        .toList();

        if (matches.isEmpty()) {
            if (live.isEmpty()) {
                throw new BootUiClientException("No running BootUI instance found for '" + trimmed
                        + "'. Is the application running in development mode?");
            }
            String running = live.stream()
                    .map(instance ->
                            instance.appName() + " (" + instance.instanceId() + " at " + instance.baseUrl() + ")")
                    .collect(Collectors.joining(", "));
            throw new BootUiClientException(
                    "No running BootUI instance found for '" + trimmed + "'. Running instances: " + running);
        }

        if (matches.size() > 1) {
            StringBuilder message = new StringBuilder("Multiple running BootUI instances match '")
                    .append(trimmed)
                    .append("'. Disambiguate with --url or a unique instanceId prefix:");
            for (Instance candidate : matches) {
                message.append("\n  - ")
                        .append(candidate.appName())
                        .append(" (instanceId ")
                        .append(candidate.instanceId())
                        .append(", pid ")
                        .append(candidate.pid())
                        .append("): --url ")
                        .append(candidate.baseUrl());
                if (!BootUiClientOptions.DEFAULT_API_PATH.equals(candidate.apiPath())) {
                    message.append(" --api-path ").append(candidate.apiPath());
                }
            }
            throw new BootUiClientException(message.toString());
        }

        Instance target = matches.get(0);
        verifyLoopbackAndHandshake(target, timeout, clientFactory);
        return target;
    }

    private static void verifyLoopbackAndHandshake(
            Instance target, Duration timeout, Function<BootUiClientOptions, BootUiClient> clientFactory) {
        String validatedBaseUrl = validateLoopbackBaseUrl(target.baseUrl());
        String validatedApiPath = validateApiPath(target.apiPath());
        if (validatedBaseUrl == null || validatedApiPath == null) {
            throw new BootUiClientException(
                    "Refusing to connect to non-loopback or invalid lease URL for '" + target.appName() + "'.");
        }
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? MAX_HANDSHAKE_TIMEOUT
                : (timeout.compareTo(MAX_HANDSHAKE_TIMEOUT) < 0 ? timeout : MAX_HANDSHAKE_TIMEOUT);
        BootUiClientOptions handshakeOptions =
                new BootUiClientOptions(validatedBaseUrl, validatedApiPath, null, effectiveTimeout);
        JsonValue overview;
        try (BootUiClient client = clientFactory.apply(handshakeOptions)) {
            overview = client.getBounded("/overview", MAX_OVERVIEW_BYTES, effectiveTimeout);
        } catch (BootUiClientException failure) {
            throw new BootUiClientException(
                    "Cannot verify BootUI instance '"
                            + target.appName()
                            + "' (instanceId "
                            + target.instanceId()
                            + ") at "
                            + validatedBaseUrl
                            + ": "
                            + failure.getMessage(),
                    failure);
        }
        String remoteInstanceId = overview.get("run").get("instanceId").asString(null);
        if (remoteInstanceId == null || !remoteInstanceId.equals(target.instanceId())) {
            throw new BootUiClientException("Instance identity mismatch for '"
                    + target.appName()
                    + "' at "
                    + validatedBaseUrl
                    + ": lease expected instanceId '"
                    + target.instanceId()
                    + "' but "
                    + validatedApiPath
                    + "/overview reported "
                    + (remoteInstanceId == null ? "no run.instanceId" : "'" + remoteInstanceId + "'")
                    + ". Refusing to send command.");
        }
    }

    private static Instance readAndVerifyLease(Path file) {
        if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return null;
        }
        String fileName = fileNamePath.toString();
        if (!fileName.endsWith(".json") || fileName.startsWith(".")) {
            return null;
        }
        String fileStem = fileName.substring(0, fileName.length() - ".json".length());
        if (!isSafeId(fileStem)) {
            return null;
        }
        String content;
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_LEASE_BYTES) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = in.readNBytes(MAX_LEASE_BYTES + 1);
                if (bytes.length > MAX_LEASE_BYTES) {
                    return null;
                }
                content = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException unreadable) {
            return null;
        }

        JsonValue parsed;
        try {
            parsed = JsonValue.parseStrict(content);
        } catch (JsonParseException invalidJson) {
            deleteQuietly(file);
            return null;
        }
        if (!parsed.isObject()) {
            deleteQuietly(file);
            return null;
        }
        for (String key : parsed.names()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("token") || lower.contains("secret") || lower.contains("authorization")) {
                deleteQuietly(file);
                return null;
            }
        }

        String instanceId = parsed.get("instanceId").asString(null);
        String runId = parsed.get("runId").asString(null);
        long pid = parsed.get("pid").asLong(-1L);
        long processStartEpochMillis = parsed.get("processStartEpochMillis").asLong(-1L);
        String appName = parsed.get("appName").asString("application");
        String framework = parsed.get("framework").asString(null);
        String baseUrl = validateLoopbackBaseUrl(parsed.get("baseUrl").asString(null));
        String apiPath = validateApiPath(parsed.get("apiPath").asString(null));
        long startedAt = parsed.get("startedAt").asLong(-1L);

        if (!fileStem.equals(instanceId)
                || !isSafeId(runId)
                || pid <= 0
                || processStartEpochMillis < 0
                || appName == null
                || appName.isBlank()
                || framework == null
                || framework.isBlank()
                || baseUrl == null
                || apiPath == null
                || startedAt <= 0) {
            deleteQuietly(file);
            return null;
        }

        if (!verifyProcessHandle(pid, processStartEpochMillis)) {
            deleteQuietly(file);
            return null;
        }

        return new Instance(
                instanceId,
                runId,
                pid,
                processStartEpochMillis,
                appName.trim(),
                framework.trim(),
                baseUrl,
                apiPath,
                startedAt);
    }

    static boolean verifyProcessHandle(long pid, long expectedStartEpochMillis) {
        if (pid <= 0 || expectedStartEpochMillis < 0) {
            return false;
        }
        Optional<ProcessHandle> handleOpt;
        try {
            handleOpt = ProcessHandle.of(pid);
        } catch (RuntimeException ex) {
            return false;
        }
        if (handleOpt.isEmpty()) {
            return false;
        }
        ProcessHandle handle = handleOpt.get();
        if (!handle.isAlive()) {
            return false;
        }
        Optional<Instant> startOpt = handle.info().startInstant();
        if (startOpt.isEmpty()) {
            return pid == ProcessHandle.current().pid();
        }
        long actualStartMillis = startOpt.get().toEpochMilli();
        return Math.abs(actualStartMillis - expectedStartEpochMillis) <= PROCESS_START_TOLERANCE_MILLIS;
    }

    static String validateLoopbackBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException invalid) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getRawPath();
        if (scheme == null
                || (!"http".equals(scheme) && !"https".equals(scheme))
                || host == null
                || !isLoopbackHost(host)
                || port < 1
                || port > 65535
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            return null;
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    static String validateApiPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String trimmed = rawPath.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("/")
                || trimmed.startsWith("//")
                || "/".equals(trimmed)
                || trimmed.contains("..")
                || trimmed.contains("\\")
                || trimmed.contains("?")
                || trimmed.contains("#")) {
            return null;
        }
        try {
            URI uri = new URI(trimmed);
            if (uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getRawPath() == null
                    || !uri.getRawPath().startsWith("/")
                    || uri.getRawPath().startsWith("//")) {
                return null;
            }
            return uri.getRawPath();
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)) {
            return true;
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                return false;
            }
            int octet = Integer.parseInt(parts[i]);
            if (octet < 0 || octet > 255) {
                return false;
            }
            if (i == 0 && octet != 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeId(String value) {
        return value != null && SAFE_ID.matcher(value).matches() && !".".equals(value) && !"..".equals(value);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
