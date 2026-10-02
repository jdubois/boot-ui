package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.BootUiInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * The opt-in baseline file ({@code docs/PLAN-v2.md} §5.8, D9): one run summary written at the end of a run, read back at
 * the next start as the previous run when the JVM's run history keeps none, so a comparison survives a full JVM
 * restart. It holds the encoded {@link RunSummary} only, which carries route templates, statement fingerprints, call
 * sites, exception-group ids and signatures, thread families, observed edges, counts, and histograms, and never
 * principals, literals, SQL text, or values.
 *
 * <p>It is written atomically, through a temporary file in the same directory, and only into a directory that exists,
 * such as the build output directory ({@code target/} or {@code build/}): a mistyped path creates nothing. It is read
 * best-effort: a missing file is no previous run, and a file written by another BootUI version or another application,
 * or one that cannot be read, is ignored with the reason.</p>
 */
public final class RunBaselineFile {

    /** The largest file read back: a run summary is at most {@link RunHistory#MAX_SUMMARY_BYTES}. */
    static final int MAX_FILE_BYTES = RunHistory.MAX_SUMMARY_BYTES + 4 * 1024;

    private static final int MAGIC = 0x42554246;

    private static final int FORMAT = 1;

    private final Path path;
    private final String application;
    private final String version;

    /**
     * @param path the file, relative to the working directory unless absolute
     * @param application the application's name, so a file another application wrote is not read as its previous run
     */
    public RunBaselineFile(Path path, String application) {
        this(path, application, BootUiInfo.VERSION);
    }

    RunBaselineFile(Path path, String application, String version) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.application = application == null || application.isBlank() ? "application" : application;
        this.version = version == null ? "unknown" : version;
    }

    /**
     * The baseline file a {@code bootui.runtime-journal.baseline-file} value names, or {@code null} when it is unset or
     * blank, which writes and reads nothing.
     */
    public static RunBaselineFile of(String configured, String application) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return new RunBaselineFile(Path.of(configured.strip()), application);
    }

    /** The file, as an absolute path. */
    public Path path() {
        return path;
    }

    /**
     * Writes {@code summary}, replacing the previous file in one step.
     *
     * @throws IOException when the file's directory does not exist or the file cannot be written
     */
    public void write(RunSummary summary) throws IOException {
        Path directory = path.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new NoSuchFileException(
                    String.valueOf(directory), null, "the baseline file's directory does not exist");
        }
        byte[] encoded = RunSummaryCodec.encode(summary, RunHistory.MAX_SUMMARY_BYTES);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length + 128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeUTF(version);
            out.writeUTF(application);
            out.writeInt(encoded.length);
            out.write(encoded);
        }
        Path temporary = Files.createTempFile(directory, ".bootui-baseline-", ".tmp");
        try {
            Files.write(temporary, bytes.toByteArray());
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Reads the file back, never throwing: what it holds, or why it holds no usable run. */
    public Read read() {
        byte[] bytes;
        try {
            if (!Files.isRegularFile(path)) {
                return new Read(null, null);
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                return ignored("it is larger than a run summary can be");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException | RuntimeException ex) {
            return ignored("it cannot be read: " + ex.getClass().getSimpleName());
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != FORMAT) {
                return ignored("it is not a BootUI baseline file of this format");
            }
            String writtenBy = in.readUTF();
            String writtenFor = in.readUTF();
            if (!version.equals(writtenBy)) {
                return ignored("BootUI " + writtenBy + " wrote it, and this is BootUI " + version);
            }
            if (!application.equals(writtenFor)) {
                return ignored(
                        "it was written for the application '" + writtenFor + "', and this is '" + application + "'");
            }
            int length = in.readInt();
            if (length < 0 || length > in.available()) {
                return ignored("it ends early");
            }
            byte[] encoded = in.readNBytes(length);
            return new Read(RunSummaryCodec.decode(encoded), null);
        } catch (IOException | RuntimeException ex) {
            return ignored("it is not a readable run summary");
        }
    }

    private Read ignored(String reason) {
        return new Read(null, "The baseline file " + path + " was ignored: " + reason + ".");
    }

    /**
     * What reading the baseline file found.
     *
     * @param summary the run it holds, or {@code null}
     * @param ignoredReason why a file that exists was not used, or {@code null}
     */
    public record Read(RunSummary summary, String ignoredReason) {}
}
