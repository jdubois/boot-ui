package sideeffectsapp;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.IOException;

/**
 * An application class outside BootUI's packages that starts a process the way the processes sensor's advice on
 * {@code ProcessBuilder.start(Redirect[])} would report it, for the engine's Side Effects tests.
 */
public final class Launcher {

    private Launcher() {}

    /** A file written, as the files sensor's advice on {@code FileOutputStream.open(String, boolean)} reports it. */
    public static void writeFile(String name) {
        long token = SideEffects.fileOpening(SideEffects.HOOK_FILE_OUTPUT_STREAM);
        SideEffects.fileOpened(token, SideEffects.HOOK_FILE_OUTPUT_STREAM, SideEffects.KIND_FILE_WRITE, name, null);
    }

    /** A system property read, as the environment sensor's advice on {@code System.getProperty} reports it. */
    public static void readProperty(String name) {
        SideEffects.environmentRead(SideEffects.HOOK_GET_PROPERTY, SideEffects.KIND_SYSTEM_PROPERTY, name);
    }

    /** An SQL statement issued here, as SQL Trace's JDBC capture reports it on the issuing thread. */
    public static void sql(String sql, io.github.jdubois.bootui.spi.CorrelationContext correlation) {
        io.github.jdubois.bootui.engine.javaagent.RequestInputSinks.sql(sql, correlation);
    }

    /** A start of {@code command} that fails, as a command that cannot be found does. */
    public static void failedStart(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        long token = SideEffects.processStarting();
        SideEffects.processStarted(
                token, builder.command(), null, new IOException("error=2, No such file or directory"));
    }
}
