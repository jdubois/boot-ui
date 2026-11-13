package io.github.jdubois.bootui.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Lists running local BootUI instances discovered in {@code ~/.bootui/instances/}, automatically
 * pruning stale or dead-PID lease files.
 */
final class InstancesCommand implements Callable<Integer> {

    private final CliContext context;

    private InstancesCommand(CliContext context) {
        this.context = context;
    }

    static void add(CliContext context, CommandSpec root) {
        InstancesCommand command = new InstancesCommand(context);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("instances");
        CommandTree.configure(context, spec);
        spec.usageMessage()
                .description(
                        "List running local BootUI instances discovered in ~/.bootui/instances.",
                        "Stale leases whose process has exited or whose start timestamp does not match are pruned automatically.")
                .footer(
                        "Examples: bootui instances",
                        "          bootui instances --json",
                        "          bootui --app my-service overview");
        root.addSubcommand("instances", new CommandLine(spec));
    }

    @Override
    public Integer call() {
        return context.listInstances();
    }
}
