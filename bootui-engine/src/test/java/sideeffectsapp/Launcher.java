package sideeffectsapp;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.IOException;

/**
 * An application class outside BootUI's packages that starts a process the way the processes sensor's advice on
 * {@code ProcessBuilder.start(Redirect[])} would report it, for the engine's Side Effects tests.
 */
public final class Launcher {

    private Launcher() {}

    /** A start of {@code command} that fails, as a command that cannot be found does. */
    public static void failedStart(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        long token = SideEffects.processStarting();
        SideEffects.processStarted(
                token, builder.command(), null, new IOException("error=2, No such file or directory"));
    }
}
