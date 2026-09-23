package io.github.jdubois.bootui.engine.vulnerabilities;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A bounded reader for a ZIP archive's central directory, used to list the entries of a library nested inside a
 * repackaged application archive without decompressing it.
 *
 * <p>{@link java.util.jar.JarInputStream} cannot list a nested archive cheaply: reaching each entry header
 * inflates the whole preceding entry, so listing names decompresses the archive. Spring Boot stores nested
 * libraries uncompressed, so their bytes can be addressed by offset; this reader reads only the end-of-central-
 * directory record and the central directory itself (at most {@link #MAX_CENTRAL_DIRECTORY_BYTES}), and
 * {@link #readEntry} inflates one entry into a caller-bounded buffer. ZIP64 archives, and any structure that does
 * not validate, are rejected with an {@link IOException} rather than guessed at.</p>
 */
public final class ZipDirectory {

    /** Upper bound on the central directory bytes read from one archive. */
    public static final int MAX_CENTRAL_DIRECTORY_BYTES = 4 * 1024 * 1024;

    private static final int END_RECORD_SIZE = 22;

    private static final int MAX_COMMENT_SIZE = 0xFFFF;

    private static final int END_SIGNATURE = 0x06054b50;

    private static final int CENTRAL_SIGNATURE = 0x02014b50;

    private static final int LOCAL_SIGNATURE = 0x04034b50;

    private static final int CENTRAL_HEADER_SIZE = 46;

    private static final int LOCAL_HEADER_SIZE = 30;

    private static final int UTF8_FLAG = 1 << 11;

    private static final int STORED = 0;

    private static final int DEFLATED = 8;

    private ZipDirectory() {}

    /** Random access to the archive's bytes: a stream positioned at {@code offset}, closed by the caller. */
    @FunctionalInterface
    public interface Source {
        InputStream open(long offset) throws IOException;
    }

    /** One central-directory entry. */
    public record Entry(String name, int method, long compressedSize, long size, long localHeaderOffset) {}

    /**
     * Lists the entries of the archive of {@code length} bytes readable from {@code source}.
     *
     * @throws IOException when the archive cannot be read, is ZIP64, or its directory does not validate
     */
    public static List<Entry> read(Source source, long length) throws IOException {
        if (length < END_RECORD_SIZE) {
            throw new IOException("Not a ZIP archive");
        }
        int tailLength = (int) Math.min(length, END_RECORD_SIZE + MAX_COMMENT_SIZE);
        long tailOffset = length - tailLength;
        byte[] tail = readFully(source, tailOffset, tailLength);
        // As the JDK and Spring Boot loaders do, the record is the last signature whose comment ends the archive.
        int end = -1;
        for (int i = tailLength - END_RECORD_SIZE; i >= 0; i--) {
            if (int32(tail, i) == END_SIGNATURE && i + END_RECORD_SIZE + uint16(tail, i + 20) == tailLength) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IOException("No end of central directory record");
        }
        int entryCount = uint16(tail, end + 10);
        if (uint16(tail, end + 4) != 0 || uint16(tail, end + 6) != 0 || uint16(tail, end + 8) != entryCount) {
            throw new IOException("Multi-disk archives are not inspected");
        }
        long directorySize = uint32(tail, end + 12);
        long directoryOffset = uint32(tail, end + 16);
        if (entryCount == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
            throw new IOException("ZIP64 archives are not inspected");
        }
        if (directorySize > MAX_CENTRAL_DIRECTORY_BYTES || directoryOffset + directorySize > tailOffset + end) {
            throw new IOException("Central directory out of bounds");
        }
        byte[] directory = readFully(source, directoryOffset, (int) directorySize);
        List<Entry> entries = new ArrayList<>(Math.min(entryCount, 1024));
        int position = 0;
        while (position < directory.length) {
            if (position + CENTRAL_HEADER_SIZE > directory.length || int32(directory, position) != CENTRAL_SIGNATURE) {
                throw new IOException("Malformed central directory");
            }
            int flags = uint16(directory, position + 8);
            int method = uint16(directory, position + 10);
            long compressedSize = uint32(directory, position + 20);
            long size = uint32(directory, position + 24);
            int nameLength = uint16(directory, position + 28);
            int extraLength = uint16(directory, position + 30);
            int commentLength = uint16(directory, position + 32);
            long localHeaderOffset = uint32(directory, position + 42);
            int nameStart = position + CENTRAL_HEADER_SIZE;
            if ((long) nameStart + nameLength + extraLength + commentLength > directory.length) {
                throw new IOException("Malformed central directory");
            }
            String name = new String(
                    directory,
                    nameStart,
                    nameLength,
                    (flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            entries.add(new Entry(name, method, compressedSize, size, localHeaderOffset));
            position = nameStart + nameLength + extraLength + commentLength;
        }
        if (position != directory.length || entries.size() != entryCount) {
            throw new IOException("Central directory entry count mismatch");
        }
        return entries;
    }

    /**
     * Reads one stored or deflated entry, failing when its content would exceed {@code maxBytes}.
     *
     * @throws IOException when the entry cannot be read, uses another method, or is larger than {@code maxBytes}
     */
    public static byte[] readEntry(Source source, Entry entry, int maxBytes) throws IOException {
        if (entry.size() > maxBytes || entry.compressedSize() > maxBytes) {
            throw new IOException("Entry exceeds " + maxBytes + " bytes");
        }
        byte[] header = readFully(source, entry.localHeaderOffset(), LOCAL_HEADER_SIZE);
        if (int32(header, 0) != LOCAL_SIGNATURE) {
            throw new IOException("Malformed local header");
        }
        long dataOffset = entry.localHeaderOffset() + LOCAL_HEADER_SIZE + uint16(header, 26) + uint16(header, 28);
        byte[] data = readFully(source, dataOffset, (int) entry.compressedSize());
        if (entry.method() == STORED) {
            return data;
        }
        if (entry.method() != DEFLATED) {
            throw new IOException("Unsupported compression method " + entry.method());
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            byte[] content = new byte[(int) entry.size()];
            int length = inflater.inflate(content);
            if (length != content.length || !inflater.finished()) {
                throw new IOException("Entry size mismatch");
            }
            return content;
        } catch (DataFormatException ex) {
            throw new IOException("Corrupt deflated entry", ex);
        } finally {
            inflater.end();
        }
    }

    private static byte[] readFully(Source source, long offset, int length) throws IOException {
        try (InputStream input = source.open(offset)) {
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) {
                throw new IOException("Unexpected end of archive");
            }
            return bytes;
        }
    }

    private static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8;
    }

    private static int int32(byte[] bytes, int offset) {
        return uint16(bytes, offset) | uint16(bytes, offset + 2) << 16;
    }

    private static long uint32(byte[] bytes, int offset) {
        return int32(bytes, offset) & 0xFFFFFFFFL;
    }
}
