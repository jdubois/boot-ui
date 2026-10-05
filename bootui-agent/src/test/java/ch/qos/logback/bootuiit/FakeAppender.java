package ch.qos.logback.bootuiit;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for a Logback file appender in the agent's forked tests (PLAN-v2 §5.16, M5-5d): its package is Logback's, so
 * the file it opens is a logging appender's, which Side Effects groups apart from the application's own files.
 */
public final class FakeAppender {

    private FakeAppender() {}

    /** Appends one line to {@code file}, as an appender opening its file does. */
    public static void append(String file, String line) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
