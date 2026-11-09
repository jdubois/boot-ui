package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/** Documentation is read directly from the release's resources, never from the working directory or network. */
final class DocsCommand implements Callable<Integer> {
    record Topic(String id, String file, String title, String description) {
        String content() throws IOException {
            try (var stream = DocsCommand.class.getResourceAsStream("/bootui-docs/" + file)) {
                if (stream == null) {
                    throw new IOException("Bundled documentation is missing: " + file);
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        JsonValue json() {
            Map<String, JsonValue> values = new LinkedHashMap<>();
            values.put("id", JsonValue.of(id));
            values.put("title", JsonValue.of(title));
            values.put("description", JsonValue.of(description));
            values.put("file", JsonValue.of(file));
            return JsonValue.object(values);
        }
    }

    static final List<Topic> TOPICS = topics();
    private final CliContext context;
    private String topic;
    private String search;

    private DocsCommand(CliContext context) {
        this.context = context;
    }

    static void add(CliContext context, CommandSpec root) {
        DocsCommand command = new DocsCommand(context);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("docs");
        CommandTree.configure(context, spec);
        spec.usageMessage()
                .description(
                        "List, read, or search this CLI version's bundled documentation. No application or network is needed.",
                        "Pages are raw Markdown, even when piped; --json explicitly selects structured output.")
                .footer("Examples: bootui docs cli", "          bootui docs --search \"blocking sensor\" --json");
        spec.addPositional(PositionalParamSpec.builder()
                .index("0")
                .arity("0..1")
                .type(String.class)
                .paramLabel("<topic>")
                .setter(CommandTree.setter((String value) -> command.topic = value))
                .build());
        spec.addOption(OptionSpec.builder("--search")
                .type(String.class)
                .paramLabel("<text>")
                .description("Case-insensitive literal search; prints file and one-based source line.")
                .setter(CommandTree.setter((String value) -> command.search = value))
                .build());
        root.addSubcommand("docs", new CommandLine(spec));
    }

    @Override
    public Integer call() throws IOException {
        if (topic != null && search != null) {
            throw new IllegalArgumentException("Choose a topic or --search, not both.");
        }
        Map<String, JsonValue> result = new LinkedHashMap<>();
        result.put("version", JsonValue.of(BootUiCli.version()));
        boolean json = context.options().explicitJson();
        if (search != null) {
            if (search.isBlank()) {
                throw new IllegalArgumentException("--search must not be blank.");
            }
            String needle = search.toLowerCase(Locale.ROOT);
            List<JsonValue> matches = new ArrayList<>();
            for (Topic page : TOPICS) {
                List<String> lines = page.content().lines().toList();
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                        Map<String, JsonValue> match = new LinkedHashMap<>();
                        match.put("topic", JsonValue.of(page.id()));
                        match.put("file", JsonValue.of(page.file()));
                        match.put("line", JsonValue.of(i + 1L));
                        match.put("text", JsonValue.of(line));
                        matches.add(JsonValue.object(match));
                        if (!json) {
                            context.out().println(page.file() + ":" + (i + 1) + ":" + line);
                        }
                    }
                }
            }
            result.put("query", JsonValue.of(search));
            result.put("matches", JsonValue.array(matches));
        } else if (topic != null) {
            Topic page = TOPICS.stream()
                    .filter(value -> value.id().equals(topic))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown documentation topic: " + topic + ". Run 'bootui docs' to list topics."));
            String content = page.content();
            result.put("topic", JsonValue.of(page.id()));
            result.put("file", JsonValue.of(page.file()));
            result.put("content", JsonValue.of(content));
            if (!json) {
                context.out().print(content);
            }
        } else {
            result.put(
                    "topics", JsonValue.array(TOPICS.stream().map(Topic::json).toList()));
            if (!json) {
                context.out().println("Bundled documentation for bootui " + BootUiCli.version() + ":");
                for (Topic page : TOPICS) {
                    context.out().println(page.id() + " - " + page.description());
                }
            }
        }
        if (json) {
            context.out().println(JsonValue.object(result).toJson());
        }
        context.out().flush();
        return ExitCodes.SUCCESS;
    }

    private static List<Topic> topics() {
        List<Topic> topics = new ArrayList<>();
        topics.add(new Topic("cli", "CLI.md", "Command line", "CLI installation, commands, output, and exit codes"));
        topics.add(
                new Topic("properties", "PROPERTIES.md", "Properties", "Configuration properties and safety controls"));
        topics.add(new Topic("setup", "SETUP.md", "Setup", "Application installation and activation"));
        topics.add(new Topic(
                "ai-agents", "AI-AGENTS.md", "AI agents", "Agent skills, MCP tools, and diagnostic workflows"));
        topics.add(new Topic("ci", "CI.md", "Continuous integration", "CI setup and assessment workflows"));
        topics.add(new Topic(
                "framework-support",
                "FRAMEWORK-SUPPORT.md",
                "Framework support",
                "Capability availability by application stack"));
        for (String feature : List.of(
                "advisors",
                "configuration",
                "database",
                "developer-tools",
                "diagnostics",
                "java-agent",
                "live-activity",
                "runtime",
                "runtime-insights",
                "scorecard",
                "security",
                "services")) {
            topics.add(new Topic(
                    feature, "features/" + feature + ".md", feature, "Feature guide: " + feature.replace('-', ' ')));
        }
        topics.add(new Topic("features", "features/README.md", "Features", "Panel groups and shared behavior"));
        for (String check : List.of(
                "ARCHITECTURE",
                "CRAC-READINESS",
                "DATABASE-ADVISOR",
                "GRAALVM-READINESS",
                "HIBERNATE",
                "JVM-TUNING",
                "MEMORY",
                "PENTEST",
                "QUARKUS-ADVISOR",
                "QUARKUS",
                "REST-API",
                "SECURITY",
                "SPRING",
                "VULNERABILITIES")) {
            String id = check.toLowerCase(Locale.ROOT) + "-checks";
            topics.add(new Topic(
                    id,
                    check + "-CHECKS.md",
                    check + " checks",
                    "Check reference: " + check.toLowerCase(Locale.ROOT).replace('-', ' ')));
        }
        return List.copyOf(topics);
    }
}
