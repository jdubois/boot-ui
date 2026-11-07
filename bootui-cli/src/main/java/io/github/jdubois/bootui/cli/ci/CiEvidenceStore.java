package io.github.jdubois.bootui.cli.ci;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Bounded, no-follow access to the files in an explicitly prepared evidence root. */
final class CiEvidenceStore {

    private CiEvidenceStore() {}

    static Path absoluteRoot(Path root) {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalArgumentException("The evidence root must be an absolute path");
        }
        Path normalized = root.normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null) {
            throw new IllegalArgumentException("The evidence root must have a parent directory");
        }
        return normalized;
    }

    static SecureDirectoryStream<Path> openRoot(Path requested) throws IOException {
        Path root = absoluteRoot(requested);
        Path parent = root.getParent().toRealPath();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
                throw new IOException("The filesystem does not support secure evidence directory access");
            }
            return secure.newDirectoryStream(root.getFileName(), LinkOption.NOFOLLOW_LINKS);
        }
    }

    static SecureDirectoryStream<Path> openDirectory(SecureDirectoryStream<Path> parent, String name)
            throws IOException {
        return parent.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS);
    }

    static Set<String> names(SecureDirectoryStream<Path> directory, int maximum) throws IOException {
        Set<String> names = new HashSet<>();
        try {
            for (Path entry : directory) {
                if (!names.add(entry.getFileName().toString()) || names.size() > maximum) {
                    throw new IOException("The evidence directory contains too many entries");
                }
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return names;
    }

    static byte[] read(SecureDirectoryStream<Path> directory, String name, int maximum) throws IOException {
        Path entry = Path.of(name);
        BasicFileAttributeView view =
                directory.getFileAttributeView(entry, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Cannot inspect the evidence file");
        }
        BasicFileAttributes before = view.readAttributes();
        requireRegular(before, maximum);
        byte[] bytes;
        try (SeekableByteChannel channel =
                directory.newByteChannel(entry, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = readBounded(channel, maximum);
        }
        BasicFileAttributes after = view.readAttributes();
        requireRegular(after, maximum);
        if (before.size() != bytes.length
                || after.size() != bytes.length
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("The evidence file changed while it was read");
        }
        return bytes;
    }

    static byte[] readRegularPath(Path path, int maximum) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireRegular(before, maximum);
        byte[] bytes;
        try (SeekableByteChannel channel =
                Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = readBounded(channel, maximum);
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireRegular(after, maximum);
        if (before.size() != bytes.length
                || after.size() != bytes.length
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("The input file changed while it was read");
        }
        return bytes;
    }

    private static void requireRegular(BasicFileAttributes attributes, int maximum) throws IOException {
        if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > maximum) {
            throw new IOException("The input must be a nonempty bounded regular file");
        }
    }

    private static byte[] readBounded(SeekableByteChannel channel, int maximum) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                throw new IOException("The input could not be read completely");
            }
        }
        if (buffer.position() == 0 || buffer.position() > maximum) {
            throw new IOException("The input exceeds its byte limit or is empty");
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    static void writeNew(SecureDirectoryStream<Path> directory, String name, byte[] bytes) throws IOException {
        Set<OpenOption> options =
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = directory.newByteChannel(Path.of(name), options)) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                if (channel.write(source) <= 0) {
                    throw new IOException("The evidence could not be written completely");
                }
            }
        }
    }

    static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String nonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
