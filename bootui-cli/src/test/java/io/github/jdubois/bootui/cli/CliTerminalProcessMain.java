package io.github.jdubois.bootui.cli;

import java.io.PrintWriter;

/** Lets the distribution tests exercise a released jar with terminal detection enabled. */
public final class CliTerminalProcessMain {
    private CliTerminalProcessMain() {}

    public static void main(String[] args) {
        System.exit(BootUiCli.run(
                args,
                System.getenv(),
                true,
                new PrintWriter(System.out, true, java.nio.charset.StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, java.nio.charset.StandardCharsets.UTF_8)));
    }
}
