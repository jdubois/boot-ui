package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.cli.ci.CiAssessmentRunner;
import io.github.jdubois.bootui.cli.ci.CiBundleEvaluator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/** Adds the explicitly client-side {@code ci} orchestration commands, outside the MCP projection. */
final class CiCommands {

    private CiCommands() {}

    static void add(CliContext context, CommandSpec root) {
        CommandSpec ci =
                newGroup("ci", "Assess one explicitly selected live local Spring application or saved bundle.");
        ci.addSubcommand("attach", new CommandLine(attach(context)));
        ci.addSubcommand("assess", new CommandLine(assess(context)));
        ci.addSubcommand("check", new CommandLine(check(context)));
        root.addSubcommand("ci", new CommandLine(ci));
    }

    private static CommandSpec attach(CliContext context) {
        Attach command = new Attach(context);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("attach");
        spec.usageMessage()
                .description(
                        "Preflight a local Spring application and atomically create an identity witness; runs no scans.")
                .footer(
                        "",
                        "The witness records the application identity before the workload. It does not prove that tests exercise this process.");
        configure(spec);
        spec.addOption(url(command.url));
        spec.addOption(apiPath(command.apiPath));
        spec.addOption(timeout(command.timeoutSeconds));
        spec.addOption(stringOption(
                "--assessment-id",
                "<id>",
                "Unique identifier for this CI assessment.",
                true,
                value -> command.assessmentId = value));
        spec.addOption(
                pathOption("--policy", "<file>", "Strict JSON policy file.", true, value -> command.policy = value));
        spec.addOption(pathOption(
                "--output",
                "<file>",
                "New witness file outside cleanable build output.",
                true,
                value -> command.output = value));
        return spec;
    }

    private static CommandSpec check(CliContext context) {
        Check command = new Check(context);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("check");
        spec.usageMessage()
                .description(
                        "Revalidate a saved assessment bundle against its exact policy without contacting the application.")
                .footer(
                        "",
                        "Exit codes: 0 pass, 1 invalid or not assessed, 3 proven threshold violation. Errors take precedence.");
        configure(spec);
        spec.addOption(pathOption("--input", "<file>", "Saved CI bundle JSON.", true, value -> command.input = value));
        spec.addOption(stringOption(
                "--assessment-id",
                "<id>",
                "Expected assessment identifier.",
                true,
                value -> command.assessmentId = value));
        spec.addOption(pathOption(
                "--policy",
                "<file>",
                "The strict JSON policy used for collection.",
                true,
                value -> command.policy = value));
        return spec;
    }

    private static CommandSpec assess(CliContext context) {
        Assess command = new Assess(context);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("assess");
        spec.usageMessage()
                .description(
                        "Verify the pre-workload witness, run only selected advisor scans, page retained findings, and write bounded JSON, Markdown, and JUnit.")
                .footer(
                        "",
                        "Exit codes: 0 pass, 1 not assessed/error, 3 proven threshold violation. Errors take precedence.");
        configure(spec);
        spec.addOption(url(command.url));
        spec.addOption(apiPath(command.apiPath));
        spec.addOption(timeout(command.timeoutSeconds));
        spec.addOption(stringOption(
                "--assessment-id",
                "<id>",
                "The same unique identifier used by attach.",
                true,
                value -> command.assessmentId = value));
        spec.addOption(pathOption(
                "--policy",
                "<file>",
                "The same strict JSON policy used by attach.",
                true,
                value -> command.policy = value));
        spec.addOption(pathOption(
                "--witness",
                "<file>",
                "Witness file created by bootui ci attach.",
                true,
                value -> command.witness = value));
        spec.addOption(pathOption(
                "--output-dir",
                "<directory>",
                "Fresh directory for bundle.json, summary.md, and junit.xml.",
                true,
                value -> command.outputDirectory = value));
        return spec;
    }

    private static CommandSpec newGroup(String name, String description) {
        UsageCommand command = new UsageCommand();
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name(name);
        command.spec = spec;
        spec.usageMessage().description(description).autoWidth(true);
        spec.mixinStandardHelpOptions(true);
        return spec;
    }

    private static void configure(CommandSpec spec) {
        spec.mixinStandardHelpOptions(true);
        spec.exitCodeOnInvalidInput(ExitCodes.ERROR);
        spec.exitCodeOnExecutionException(ExitCodes.ERROR);
        spec.usageMessage().autoWidth(true);
    }

    private static OptionSpec url(String[] target) {
        return OptionSpec.builder("--url")
                .paramLabel("<url>")
                .type(String.class)
                .required(true)
                .description("Explicit HTTP(S) URL with a literal loopback address and port.")
                .setter(setter((String value) -> target[0] = value))
                .build();
    }

    private static OptionSpec apiPath(String[] target) {
        return OptionSpec.builder("--api-path")
                .paramLabel("<path>")
                .type(String.class)
                .defaultValue("/bootui/api")
                .description("BootUI API path; default /bootui/api.")
                .setter(setter((String value) -> target[0] = value))
                .build();
    }

    private static OptionSpec timeout(int[] target) {
        return OptionSpec.builder("--timeout")
                .paramLabel("<seconds>")
                .type(Integer.class)
                .defaultValue("60")
                .description("Per-request timeout in seconds (1-180); total collection budget is three minutes.")
                .converters(value -> {
                    try {
                        int seconds = Integer.parseInt(value);
                        if (seconds < 1 || seconds > 180) {
                            throw new CommandLine.TypeConversionException("must be between 1 and 180 seconds");
                        }
                        return seconds;
                    } catch (NumberFormatException invalid) {
                        throw new CommandLine.TypeConversionException("'" + value + "' is not a number of seconds");
                    }
                })
                .setter(setter((Integer value) -> target[0] = value))
                .build();
    }

    private static OptionSpec stringOption(
            String name,
            String label,
            String description,
            boolean required,
            java.util.function.Consumer<String> target) {
        return OptionSpec.builder(name)
                .paramLabel(label)
                .type(String.class)
                .required(required)
                .description(description)
                .setter(setter((String value) -> target.accept(value)))
                .build();
    }

    private static OptionSpec pathOption(
            String name, String label, String description, boolean required, java.util.function.Consumer<Path> target) {
        return OptionSpec.builder(name)
                .paramLabel(label)
                .type(Path.class)
                .required(required)
                .description(description)
                .setter(setter((Path value) -> target.accept(value)))
                .build();
    }

    private static <T> CommandLine.Model.ISetter setter(java.util.function.Consumer<T> consumer) {
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

    private static final class Attach implements Callable<Integer> {

        private final CliContext context;
        private final String[] url = new String[1];
        private final String[] apiPath = {"/bootui/api"};
        private final int[] timeoutSeconds = {60};
        private String assessmentId;
        private Path policy;
        private Path output;

        Attach(CliContext context) {
            this.context = context;
        }

        @Override
        public Integer call() {
            int result = new CiAssessmentRunner(
                            url[0], apiPath[0], timeoutSeconds[0], assessmentId, policy, output, null)
                    .attach();
            if (result == 0) {
                context.out().println("BootUI target attached; no scans were run.");
            } else {
                context.err().println("BootUI attach failed; do not assess with this witness unless attach succeeds.");
            }
            return result;
        }
    }

    private static final class Assess implements Callable<Integer> {

        private final CliContext context;
        private final String[] url = new String[1];
        private final String[] apiPath = {"/bootui/api"};
        private final int[] timeoutSeconds = {60};
        private String assessmentId;
        private Path policy;
        private Path witness;
        private Path outputDirectory;

        Assess(CliContext context) {
            this.context = context;
        }

        @Override
        public Integer call() {
            int result = new CiAssessmentRunner(
                            url[0], apiPath[0], timeoutSeconds[0], assessmentId, policy, witness, outputDirectory)
                    .assess();
            if (result == 0) {
                context.out().println("BootUI assessment passed; bounded reports are ready.");
            } else if (result == 3) {
                context.err().println("BootUI assessment found a policy-threshold violation; reports are ready.");
            } else if (outputDirectory != null && Files.isRegularFile(outputDirectory.resolve("bundle.json"))) {
                context.err().println("BootUI assessment could not be completed; inspect the bounded reports.");
            } else {
                context.err().println("BootUI assessment could not be completed; no valid reports were written.");
            }
            return result;
        }
    }

    private static final class Check implements Callable<Integer> {

        private final CliContext context;
        private Path input;
        private String assessmentId;
        private Path policy;

        Check(CliContext context) {
            this.context = context;
        }

        @Override
        public Integer call() {
            CiBundleEvaluator.Result result;
            try {
                result = CiBundleEvaluator.evaluate(input, assessmentId, policy);
            } catch (IOException failure) {
                context.err().println("BootUI offline bundle could not be read or verified.");
                return ExitCodes.ERROR;
            }
            if (result.exitCode() == ExitCodes.SUCCESS) {
                context.out().println("BootUI offline assessment passed.");
            } else if (result.exitCode() == ExitCodes.THRESHOLD) {
                context.err().println("BootUI offline assessment found a policy-threshold violation.");
            } else {
                context.err().println("BootUI offline bundle is invalid or the assessment was not completed.");
            }
            return result.exitCode();
        }
    }

    private static final class UsageCommand implements Callable<Integer> {

        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return ExitCodes.ERROR;
        }
    }
}
