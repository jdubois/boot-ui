package io.github.jdubois.bootui.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Builds the picocli command tree from the manifest.
 *
 * <p>Built programmatically rather than from annotated classes for two reasons. It makes the projection
 * literal — there is no per-tool class that could be written by hand and drift from the registry, only a loop
 * over the manifest — and it keeps the CLI free of runtime reflection over its own commands, which is what
 * leaves a native-image build as a build-file change rather than a rewrite.
 */
final class CommandTree {

    private CommandTree() {}

    static CommandSpec build(CliContext context) {
        CommandSpec root = group(context, "bootui", "Ask a running Spring Boot or Quarkus application a question.");
        root.usageMessage()
                .description(
                        "Ask a running Spring Boot or Quarkus application a question.",
                        "",
                        "Every command is one BootUI MCP tool. Point --url at the application "
                                + "(default http://localhost:8080) and it answers over its local-only HTTP endpoint.",
                        "Output is JSON when piped or with --json, and readable otherwise.")
                .footer(
                        "",
                        "Exit codes: 0 answered, 1 usage or transport error, 2 refused by BootUI's panel policy.",
                        "Run 'bootui tools' to see what a specific application actually exposes.");

        List<CommandSpec> toolGroups = new ArrayList<>();
        for (ToolManifest.Tool tool : context.manifest().tools()) {
            List<String> path = tool.path();
            CommandSpec parent = root;
            for (int i = 0; i < path.size() - 1; i++) {
                parent = childGroup(context, parent, path.get(i), toolGroups);
            }
            parent.addSubcommand(path.get(path.size() - 1), new CommandLine(toolCommand(context, tool)));
        }

        // A group has no meaning of its own, so the useful thing to show next to it is what it contains.
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
        listCommands(root, context.manifest().tools(), true);
        return root;
    }

    /** The words of a group's command path below {@code bootui}, such as {@code memory heap}. */
    private static List<String> groupPath(CommandSpec group) {
        List<String> words = new ArrayList<>();
        for (CommandSpec spec = group; spec.parent() != null; spec = spec.parent()) {
            words.add(0, spec.name());
        }
        return words;
    }

    /**
     * Replaces picocli's one-word-per-subcommand list with every command beneath {@code spec}, each with its
     * arguments, what it returns, where its id comes from, and one example (M4-21). Agents in the benchmark spent
     * almost half their calls walking {@code --help} one level at a time; this answers in one.
     */
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

    /**
     * A command that only groups others.
     *
     * <p>Running one on its own is a usage error rather than a no-op, so a typo like {@code bootui memory}
     * fails a script instead of quietly succeeding.
     */
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
                .description(HelpListing.literal(tool.summary()))
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
        configure(context, spec);
        return spec;
    }

    /** Empty for a command every stack has, otherwise {@code " (only on spring mvc, spring webflux)"}. */
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

    /**
     * Adds the global options and the exit-code policy to one command.
     *
     * <p>The options go on every command, not only the root, so {@code bootui beans --url …} works: people
     * type the target after the question at least as often as before it.
     */
    private static void configure(CliContext context, CommandSpec spec) {
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
                        .description("Print the application's JSON verbatim. Implied when output is not a terminal.")
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
            spec.addOption(option);
        }
    }

    /**
     * Reads {@code --timeout}, rejecting values a {@code Duration} would accept but the caller cannot have
     * meant: zero or negative seconds either fails every call instantly or waits forever.
     */
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

    private static <T> CommandLine.Model.ISetter setter(Consumer<T> consumer) {
        return new CommandLine.Model.ISetter() {
            @Override
            @SuppressWarnings("unchecked")
            public <V> V set(V value) {
                // Ignore nulls: picocli resets a command's own options as it descends into it, and the
                // global options are attached to every command, so a reset must not wipe a value the
                // user gave earlier on the line.
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

    /** A command group invoked without a subcommand. */
    private static final class UsageCommand implements Callable<Integer> {

        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return ExitCodes.ERROR;
        }
    }

    /** One tool call. */
    private static final class ToolCommand implements Callable<Integer> {

        private final CliContext context;
        private final ToolManifest.Tool tool;

        private String query;
        private Integer limit;
        private String id;
        private String scanId;
        private Integer offset;

        ToolCommand(CliContext context, ToolManifest.Tool tool) {
            this.context = context;
            this.tool = tool;
        }

        @Override
        public Integer call() {
            return context.invokeTool(tool, query, limit, id, scanId, offset);
        }
    }
}
