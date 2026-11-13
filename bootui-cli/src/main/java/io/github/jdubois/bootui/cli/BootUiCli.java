package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

public final class BootUiCli {

    private BootUiCli() {}

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, java.nio.charset.StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, java.nio.charset.StandardCharsets.UTF_8);
        int exitCode = run(args, System.getenv(), System.console() != null, out, err);
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    public static int run(
            String[] args, Map<String, String> environment, boolean terminal, PrintWriter out, PrintWriter err) {
        return run(args, environment, terminal, out, err, BootUiClient::new, BootUiClient::direct);
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clients) {
        return run(args, environment, terminal, out, err, clients, clients);
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clients,
            Function<BootUiClientOptions, BootUiClient> directClients) {
        CliContext context =
                new CliContext(ToolManifest.bundled(), environment, terminal, out, err, clients, directClients);
        CommandSpec root = CommandTree.build(context);
        root.version("bootui " + version());

        UpdateCheck[] updateCheck = new UpdateCheck[1];

        CommandLine commandLine = new CommandLine(root)
                .setOut(out)
                .setErr(err)
                .setColorScheme(CommandLine.Help.defaultColorScheme(
                        terminal ? CommandLine.Help.Ansi.AUTO : CommandLine.Help.Ansi.OFF))
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionStrategy(parsed -> {
                    CommandLine.ParseResult leaf = parsed;
                    boolean help = false;
                    boolean local = false;
                    while (leaf != null) {
                        help |= leaf.isUsageHelpRequested() || leaf.isVersionHelpRequested();
                        local |= switch (leaf.commandSpec().name()) {
                            case "docs", "update", "ci", "init", "instances" -> true;
                            default -> false;
                        };
                        leaf = leaf.subcommand();
                    }
                    if (!help && !local) {
                        updateCheck[0] =
                                UpdateCheck.create(version(), environment, updateCheckTerminal(args, terminal));
                        updateCheck[0].startRefreshIfDue();
                    }
                    return new CommandLine.RunLast().execute(parsed);
                })
                .setExecutionExceptionHandler((exception, failed, parseResult) -> {
                    err.println(exception.getMessage() == null ? exception.toString() : exception.getMessage());
                    if (context.options().verbose()) {
                        exception.printStackTrace(err);
                    }
                    err.flush();
                    return ExitCodes.ERROR;
                });
        int exitCode = commandLine.execute(args);
        if (updateCheck[0] != null) {
            updateCheck[0].report(err);
            updateCheck[0].awaitRefresh();
        }
        return exitCode;
    }

    static String version() {
        String version = BootUiCli.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    static boolean updateCheckTerminal(String[] args, boolean terminal) {
        return terminal
                && !Arrays.asList(args).contains("ci")
                && Arrays.stream(args).noneMatch(argument -> argument.startsWith("@"));
    }
}
