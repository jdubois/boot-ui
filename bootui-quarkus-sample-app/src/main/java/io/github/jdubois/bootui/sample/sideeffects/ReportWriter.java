package io.github.jdubois.bootui.sample.sideeffects;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Side Effects' seeded files and environment reads ({@code docs/PLAN-v2.md} §5.16, M5-5d): {@link #writeReport()} reads
 * the {@code sample.report.title} system property and writes a dated report under {@code target/bootui-side-effects},
 * in the working directory, outside the temporary directory, so Side Effects shows a {@code write} of
 * {@code ./target/bootui-side-effects/report-{n}-{n}-{n}.csv} and a read of {@code sample.report.title}, never the file's
 * contents or the property's value. The counterexamples: {@link #scratch()} writes and deletes a temporary file, which
 * shows under the temporary directory, and {@link #log()} writes through a JDK logging file handler, which is grouped
 * apart as logging.
 */
@ApplicationScoped
public class ReportWriter {

    static final String TITLE_PROPERTY = "sample.report.title";

    /** The report's contents, which Side Effects never reads. */
    static final String CONTENTS = "order,total\nsample-side-effects-contents-never-shown,42\n";

    /** Writes today's report and returns where, relative to the working directory. */
    public String writeReport() {
        String title = System.getProperty(TITLE_PROPERTY, "Orders");
        Path report = Path.of("target", "bootui-side-effects", "report-" + LocalDate.now() + ".csv");
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, "# " + title + "\n" + CONTENTS, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return report.toString();
    }

    /** The counterexample: a scratch file in the temporary directory, deleted at once. */
    public String scratch() {
        try {
            Path scratch = Files.createTempFile("bootui-scratch-", ".tmp");
            Files.writeString(scratch, CONTENTS, StandardCharsets.UTF_8);
            Files.delete(scratch);
            return "deleted";
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The counterexample: a logging handler's file, which Side Effects groups apart from the application's. */
    public String log() {
        try {
            FileHandler handler = new FileHandler(
                    Path.of(System.getProperty("java.io.tmpdir"), "bootui-sample-%u.log")
                            .toString(),
                    true);
            try {
                handler.publish(new LogRecord(Level.INFO, "Side Effects logging counterexample"));
            } finally {
                handler.close();
            }
            return "logged";
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
