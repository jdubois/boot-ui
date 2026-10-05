package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Starts a short local process: the JDK's own {@code java -version}, never a shell, so the seed works wherever the
 * sample runs. Side Effects shows it as a {@code java} process started from {@link #version()}; its arguments never
 * appear ({@code docs/PLAN-v2.md} §5.16).
 */
@Service
public class JavaVersionReporter {

    private static final String SECRET_ARGUMENT = "-Dsample.side-effects.token=never-shown-by-bootui";

    /** The first line {@code java -version} prints, or a message when it could not run. */
    public String version() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try {
            Process process = new ProcessBuilder(java, SECRET_ARGUMENT, "-version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "java -version timed out";
            }
            return output.lines().findFirst().orElse("");
        } catch (IOException ex) {
            return "java -version could not start: " + ex.getMessage();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    /** The counterexample: the same answer from the running JVM, with no process. */
    public String runtimeVersion() {
        return Runtime.version().toString();
    }
}
