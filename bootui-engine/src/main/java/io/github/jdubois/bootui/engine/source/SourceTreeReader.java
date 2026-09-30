package io.github.jdubois.bootui.engine.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Budgeted, symlink-refusing access to one module's source tree during an explicit scan. Every directory
 * entry and every byte read counts against one shared budget, and the reader never follows a symbolic link:
 * meeting one fails the lookup, because a skipped subtree could hold a conflicting declaration.
 */
public final class SourceTreeReader {

    /** Budgets for one lookup; each must be positive. */
    public record Limits(int entries, int depth, int fileBytes, int totalBytes) {
        public Limits {
            if (entries < 1 || depth < 1 || fileBytes < 1 || totalBytes < 1) {
                throw new IllegalArgumentException("Source lookup limits must be positive.");
            }
        }
    }

    /** Receives each regular file the walk reaches. */
    @FunctionalInterface
    public interface Visitor {
        void visit(Path path) throws IOException;
    }

    /** A lookup budget ran out; {@link #kind()} names which one. */
    public static final class LimitException extends IOException {
        private final String kind;

        LimitException(String kind, String message) {
            super(message);
            this.kind = kind;
        }

        /** {@code depth}, {@code entry}, {@code file-byte}, or {@code total-byte}. */
        public String kind() {
            return kind;
        }
    }

    private final Limits limits;
    private final String subject;
    private final String consequence;
    private int entries;
    private int bytes;
    private int symbolicLinks;

    /**
     * @param subject how limit messages name the lookup, for example {@code Generated-source}
     * @param consequence what a limit means for the result, appended to every limit message
     */
    public SourceTreeReader(Limits limits, String subject, String consequence) {
        this.limits = limits;
        this.subject = subject;
        this.consequence = consequence;
    }

    /**
     * Whether {@code directory} is an existing directory inside {@code module} reached without passing through a
     * symbolic link.
     *
     * @throws IOException when a symbolic link lies on the way
     */
    public boolean safeDirectory(Path module, Path directory) throws IOException {
        Path current = module;
        if (!directory.startsWith(module)) return false;
        for (Path segment : module.relativize(directory)) {
            if (!directory(current)) return false;
            current = current.resolve(segment);
        }
        return directory(current);
    }

    /** How many symbolic links this reader refused so far; each one failed the lookup that met it. */
    public int symbolicLinks() {
        return symbolicLinks;
    }

    /** Visits every regular file under {@code directory}, descending only where {@code descend} allows. */
    public void walk(Path directory, Visitor visitor, Predicate<Path> descend) throws IOException {
        walk(directory, 0, visitor, descend);
    }

    private void walk(Path directory, int depth, Visitor visitor, Predicate<Path> descend) throws IOException {
        if (depth > limits.depth()) throw limit("depth");
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++entries > limits.entries()) throw limit("entry");
                if (!descend.test(child)) continue;
                BasicFileAttributes attributes =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // A skipped subtree could contain a conflicting declaration.
                if (attributes.isSymbolicLink()) {
                    symbolicLinks++;
                    throw new IOException("Symbolic source entry");
                }
                if (attributes.isDirectory()) walk(child, depth + 1, visitor, descend);
                else if (attributes.isRegularFile()) visitor.visit(child);
            }
        }
    }

    /** Reads one UTF-8 source file within the per-file and total byte budgets, without following links. */
    public String read(Path path) throws IOException {
        int remaining = Math.min(limits.fileBytes(), limits.totalBytes() - bytes);
        if (remaining <= 0) throw limit("total-byte");
        ByteBuffer buffer = ByteBuffer.allocate(remaining + 1);
        try (SeekableByteChannel channel =
                Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read at most one byte beyond the budget, even if the source grows during lookup.
            }
        }
        bytes += buffer.position();
        if (buffer.position() > remaining) {
            throw limit(remaining == limits.fileBytes() ? "file-byte" : "total-byte");
        }
        buffer.flip();
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(buffer)
                .toString();
    }

    private boolean directory(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return false;
        }
        if (attributes.isSymbolicLink()) {
            symbolicLinks++;
            throw new IOException("Symbolic source root");
        }
        return attributes.isDirectory();
    }

    private LimitException limit(String kind) {
        return new LimitException(kind, subject + " " + kind + " limit reached; " + consequence);
    }
}
